package com.codecompass.analyzer.java;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.DependencyEdge;
import com.codecompass.analyzer.FailedFile;
import com.codecompass.analyzer.FieldInfo;
import com.codecompass.analyzer.LanguageAnalyzer;
import com.codecompass.analyzer.MethodInfo;
import com.codecompass.repo.CodeUnitFileInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;

/**
 * MVP 唯一的 {@link LanguageAnalyzer} 实现：语法级 Java 解析。
 *
 * <p>JavaParser 的类型**只出现在本包内**，绝不泄漏到业务层 —— 这是 T0 定下的
 * {@code analyzer/<language>/} 物理隔离约定。
 *
 * <p>两遍处理：第一遍逐文件解析出单元/方法与"待解析引用"，第二遍用全限定名索引把引用
 * 解析成边。需要两遍是因为引用可能指向后面才解析到的文件。
 *
 * <p>不做符号求解（§05 预研 2），名字解析按 Java 可见性规则给出候选顺序，
 * 由 {@link JavaNameResolver} 负责。
 */
public class JavaSpringAnalyzer implements LanguageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JavaSpringAnalyzer.class);

    private static final String LANGUAGE = "java";
    private static final String KIND_FIELD = "field";
    private static final String KIND_ANNOTATION = "annotation";
    private static final String KIND_IMPORT = "import";

    /** 越小越优先。同一对类只留一条边，保留信息量最大的 kind。 */
    private static final List<String> KIND_PRIORITY = List.of(KIND_FIELD, KIND_ANNOTATION, KIND_IMPORT);

    private static final int REASON_LIMIT = 500;
    private static final int PROBLEM_LIMIT = 3;

    private final JavaAnalyzeProperties properties;

    public JavaSpringAnalyzer(JavaAnalyzeProperties properties) {
        this.properties = properties;
    }

    @Override
    public String language() {
        return LANGUAGE;
    }

    @Override
    public AnalyzeResult analyze(AnalyzeRequest request) {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(JavaLanguageLevels.resolve(properties.getJava().getLanguageLevel()));
        JavaParser parser = new JavaParser(configuration);

        List<CodeUnitInfo> parsedUnits = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        List<FailedFile> failedFiles = new ArrayList<>();
        List<PendingReference> pending = new ArrayList<>();

        for (CodeUnitFileInfo file : request.files()) {
            try {
                parseFile(parser, request.repositoryRoot(), file, request.repositoryId(),
                        parsedUnits, methods, pending);
            } catch (Exception e) {
                // 每文件一个 try：坏文件只损失它自己，绝不作废整次分析。
                // 捕 Exception 而不是只捕 ParseProblemException —— 缺文件、行范围缺失等
                // 都会以别的异常类型出现，逃逸出去就变成 500。
                String reason = summarize(e);
                log.warn("解析失败，跳过：{}（{}）", file.relativePath(), reason);
                failedFiles.add(new FailedFile(file.relativePath(), reason));
            }
        }

        String framework = detectFramework(parsedUnits);
        List<CodeUnitInfo> units = parsedUnits.stream()
                .map(unit -> withFramework(unit, framework))
                .toList();

        return new AnalyzeResult(request.repositoryId(), LANGUAGE, framework, units, methods,
                resolveDependencies(units, pending, request.repositoryId()), failedFiles);
    }

    // ---------- 第一遍：解析 ----------

    private void parseFile(JavaParser parser,
                           Path repositoryRoot,
                           CodeUnitFileInfo file,
                           String repositoryId,
                           List<CodeUnitInfo> units,
                           List<MethodInfo> methods,
                           List<PendingReference> pending) throws IOException {
        Path absolute = repositoryRoot.resolve(file.relativePath());
        ParseResult<CompilationUnit> parsed = parser.parse(absolute);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            throw new IllegalStateException(problemSummary(parsed.getProblems()));
        }
        CompilationUnit compilationUnit = parsed.getResult().orElseThrow();

        String packageName = compilationUnit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");

        List<String> singleTypeImports = new ArrayList<>();
        List<String> wildcardImports = new ArrayList<>();
        for (ImportDeclaration imported : compilationUnit.getImports()) {
            if (imported.isAsterisk()) {
                wildcardImports.add(imported.getNameAsString());
            } else {
                singleTypeImports.add(imported.getNameAsString());
            }
        }
        JavaNameResolver resolver = new JavaNameResolver(packageName, singleTypeImports, wildcardImports);

        List<String> typeIdsInFile = new ArrayList<>();

        for (TypeDeclaration<?> type : compilationUnit.getTypes()) {
            String simpleName = type.getNameAsString();
            String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            // id 必须含 filePath：多模块下同一个全限定名可能出现在两个模块里
            String unitId = repositoryId + ":" + file.relativePath() + "#" + qualifiedName;
            typeIdsInFile.add(unitId);

            units.add(new CodeUnitInfo(unitId, repositoryId, file.relativePath(), LANGUAGE, "",
                    packageName, simpleName, kindOf(type), annotationNames(type.getAnnotations()),
                    fieldsOf(type, unitId, resolver, pending),
                    startLine(type), endLine(type)));

            for (AnnotationExpr annotation : type.getAnnotations()) {
                pending.add(new PendingReference(unitId, KIND_ANNOTATION,
                        resolver.candidates(annotation.getNameAsString())));
            }

            for (MethodDeclaration method : type.getMethods()) {
                String signature = method.getDeclarationAsString();
                methods.add(new MethodInfo(unitId + "#" + signature, unitId, method.getNameAsString(),
                        signature, annotationNames(method.getAnnotations()),
                        startLine(method), endLine(method)));
                for (AnnotationExpr annotation : method.getAnnotations()) {
                    pending.add(new PendingReference(unitId, KIND_ANNOTATION,
                            resolver.candidates(annotation.getNameAsString())));
                }
            }

            for (ConstructorDeclaration constructor : type.getConstructors()) {
                String signature = constructor.getDeclarationAsString();
                methods.add(new MethodInfo(unitId + "#" + signature, unitId,
                        constructor.getNameAsString(), signature,
                        annotationNames(constructor.getAnnotations()),
                        startLine(constructor), endLine(constructor)));
            }
        }

        // import 作用于整个编译单元，因此该文件里每个顶层类型都算依赖它。
        // 通配 import 不产生边：一个 com.x.* 会连到该包下所有类，属于过度生成，
        // 它只参与名字解析。
        for (String unitId : typeIdsInFile) {
            for (String imported : singleTypeImports) {
                pending.add(new PendingReference(unitId, KIND_IMPORT, resolver.candidates(imported)));
            }
        }
    }

    private static List<FieldInfo> fieldsOf(TypeDeclaration<?> type,
                                            String unitId,
                                            JavaNameResolver resolver,
                                            List<PendingReference> pending) {
        List<FieldInfo> fields = new ArrayList<>();
        for (FieldDeclaration field : type.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                fields.add(new FieldInfo(variable.getNameAsString(), variable.getType().asString(),
                        annotationNames(field.getAnnotations())));
                collectTypeNames(variable.getType(), typeName -> pending.add(
                        new PendingReference(unitId, KIND_FIELD, resolver.candidates(typeName))));
            }
        }
        return fields;
    }

    // ---------- 第二遍：引用解析成边 ----------

    private List<DependencyEdge> resolveDependencies(List<CodeUnitInfo> units,
                                                     List<PendingReference> pending,
                                                     String repositoryId) {
        Map<String, String> idByQualifiedName = indexByQualifiedName(units);

        Map<UnitPair, String> bestKindByPair = new HashMap<>();
        for (PendingReference reference : pending) {
            String targetId = firstPresent(reference.candidateQualifiedNames(), idByQualifiedName);
            if (targetId == null || targetId.equals(reference.fromCodeUnitId())) {
                // 仓库外引用按 T3 决策丢弃；自引用不画自环（依赖图里是噪声）
                continue;
            }
            UnitPair pair = new UnitPair(reference.fromCodeUnitId(), targetId);
            bestKindByPair.merge(pair, reference.kind(),
                    (existing, candidate) -> priority(candidate) < priority(existing) ? candidate : existing);
        }

        return bestKindByPair.entrySet().stream()
                .map(entry -> new DependencyEdge(
                        entry.getKey().from() + "->" + entry.getKey().to(),
                        repositoryId, entry.getKey().from(), entry.getKey().to(),
                        entry.getValue(), LANGUAGE))
                .sorted(Comparator.comparing(DependencyEdge::id))
                .toList();
    }

    private static Map<String, String> indexByQualifiedName(List<CodeUnitInfo> units) {
        Map<String, String> index = new HashMap<>();
        for (CodeUnitInfo unit : units) {
            String qualifiedName = unit.packageName().isEmpty()
                    ? unit.name()
                    : unit.packageName() + "." + unit.name();
            String previous = index.putIfAbsent(qualifiedName, unit.id());
            if (previous != null) {
                // 语法级解析无法区分两个模块里的同名类，只能记录歧义
                log.warn("全限定名重复，指向它的边可能落在其中一个上：{}（{} / {}）",
                        qualifiedName, previous, unit.id());
            }
        }
        return index;
    }

    private static String firstPresent(List<String> candidates, Map<String, String> index) {
        for (String candidate : candidates) {
            String id = index.get(candidate);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private static int priority(String kind) {
        int position = KIND_PRIORITY.indexOf(kind);
        return position < 0 ? KIND_PRIORITY.size() : position;
    }

    // ---------- 框架识别 ----------

    private String detectFramework(List<CodeUnitInfo> units) {
        Map<String, List<String>> markers = properties.getFrameworkMarkers();
        if (markers == null || markers.isEmpty() || units.isEmpty()) {
            return "";
        }
        String best = "";
        long bestHits = 0;
        // 按字典序遍历 + 严格大于：并列时取字典序在前者，使结果与 Map 迭代顺序无关
        for (String framework : new TreeSet<>(markers.keySet())) {
            List<String> frameworkMarkers = markers.get(framework);
            if (frameworkMarkers == null || frameworkMarkers.isEmpty()) {
                continue;
            }
            long hits = units.stream()
                    .filter(unit -> unit.annotations().stream().anyMatch(frameworkMarkers::contains))
                    .count();
            if (hits > bestHits) {
                bestHits = hits;
                best = framework;
            }
        }
        return bestHits > 0 ? best : "";
    }

    private static CodeUnitInfo withFramework(CodeUnitInfo unit, String framework) {
        return new CodeUnitInfo(unit.id(), unit.repositoryId(), unit.filePath(), unit.language(),
                framework, unit.packageName(), unit.name(), unit.kind(), unit.annotations(),
                unit.fields(), unit.startLine(), unit.endLine());
    }

    // ---------- AST 细节 ----------

    private static String kindOf(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.isInterface() ? "interface" : "class";
        }
        if (type instanceof EnumDeclaration) {
            return "enum";
        }
        if (type instanceof RecordDeclaration) {
            return "record";
        }
        if (type instanceof AnnotationDeclaration) {
            return "annotation";
        }
        return "type";
    }

    /** 注解只保留简单名并加 {@code @} 前缀，与 SCHEMA.md 的 {@code "@Controller"} 一致。 */
    private static List<String> annotationNames(List<AnnotationExpr> annotations) {
        return annotations.stream().map(annotation -> {
            String name = annotation.getNameAsString();
            int lastDot = name.lastIndexOf('.');
            return "@" + (lastDot < 0 ? name : name.substring(lastDot + 1));
        }).toList();
    }

    /** 递归收集类型名：泛型参数与数组元素都要取到，否则 {@code List<Owner>} 会解析不出 Owner。 */
    private static void collectTypeNames(Type type, Consumer<String> sink) {
        if (type instanceof ArrayType arrayType) {
            collectTypeNames(arrayType.getComponentType(), sink);
        } else if (type instanceof ClassOrInterfaceType classType) {
            sink.accept(classType.getNameWithScope());
            classType.getTypeArguments().ifPresent(arguments -> arguments.forEach(argument -> {
                if (argument instanceof ClassOrInterfaceType argumentType) {
                    collectTypeNames(argumentType, sink);
                } else if (argument instanceof WildcardType wildcard) {
                    wildcard.getExtendedType().ifPresent(bound -> collectTypeNames(bound, sink));
                }
            }));
        } else if (type instanceof WildcardType wildcard) {
            wildcard.getExtendedType().ifPresent(bound -> collectTypeNames(bound, sink));
        }
    }

    private static int startLine(Node node) {
        return lineRange(node).begin.line;
    }

    private static int endLine(Node node) {
        return lineRange(node).end.line;
    }

    private static com.github.javaparser.Range lineRange(Node node) {
        Optional<com.github.javaparser.Range> range = node.getRange();
        if (range.isEmpty()) {
            // T3 的构造器不接受 startLine < 1，所以这里必须响亮失败而不是填 0
            throw new IllegalStateException(
                    "无法确定 " + node.getClass().getSimpleName() + " 的行范围");
        }
        return range.get();
    }

    // ---------- 错误摘要 ----------

    private static String problemSummary(List<Problem> problems) {
        if (problems.isEmpty()) {
            return "解析失败（无详细信息）";
        }
        return truncate(problems.stream()
                .limit(PROBLEM_LIMIT)
                .map(Problem::getMessage)
                .collect(Collectors.joining("; ")));
    }

    private static String summarize(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return truncate(message.replaceAll("\\s+", " ").trim());
    }

    private static String truncate(String value) {
        return value.length() <= REASON_LIMIT
                ? value
                : value.substring(0, REASON_LIMIT) + "…";
    }

    /** 待解析的引用：属于哪个单元、什么 kind、按优先级排好的候选全限定名。 */
    private record PendingReference(String fromCodeUnitId, String kind, List<String> candidateQualifiedNames) {
    }

    /** 用记录而不是拼字符串做去重键 —— 路径里理论上可能出现任何分隔符。 */
    private record UnitPair(String from, String to) {
    }
}
