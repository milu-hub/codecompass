package com.codecompass.analyzer.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.DependencyEdge;
import com.codecompass.analyzer.MethodInfo;
import com.codecompass.repo.CodeUnitFileInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JavaSpringAnalyzer：语法级 Java 解析。
 *
 * 这是 T3 那批契约第一次被真正兑现的地方 —— 1-based 行号、确定性 id、失败隔离。
 */
class JavaSpringAnalyzerTest {

    @TempDir
    Path repoRoot;

    private static final Map<String, List<String>> SPRING_MARKERS = Map.of(
            "spring", List.of("@SpringBootApplication", "@RestController", "@Controller",
                    "@Service", "@Repository", "@Component", "@Configuration", "@Bean", "@Autowired"));

    private JavaSpringAnalyzer analyzer() {
        return analyzer(SPRING_MARKERS);
    }

    private JavaSpringAnalyzer analyzer(Map<String, List<String>> frameworkMarkers) {
        JavaAnalyzeProperties properties = new JavaAnalyzeProperties();
        properties.setFrameworkMarkers(frameworkMarkers);
        return new JavaSpringAnalyzer(properties);
    }

    private void write(String relativePath, String source) throws IOException {
        Path target = repoRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, source);
    }

    private AnalyzeResult analyze(String... relativePaths) {
        List<CodeUnitFileInfo> files = java.util.Arrays.stream(relativePaths)
                .map(path -> new CodeUnitFileInfo(path, "", "", "java"))
                .toList();
        return analyzer().analyze(new AnalyzeRequest("repo-1", repoRoot, files));
    }

    // ---------- 提取 ----------

    @Test
    @DisplayName("language 固定为 java，包名/类名/类型/注解均从 AST 提取")
    void extractsPackageNameKindAndAnnotations() throws IOException {
        write("src/main/java/com/example/OwnerController.java", """
                package com.example;

                @RestController
                @Deprecated
                public class OwnerController {
                }
                """);

        AnalyzeResult result = analyze("src/main/java/com/example/OwnerController.java");

        assertThat(result.language()).isEqualTo("java");
        assertThat(result.codeUnits()).hasSize(1);
        CodeUnitInfo unit = result.codeUnits().get(0);
        assertThat(unit.packageName()).isEqualTo("com.example");
        assertThat(unit.name()).isEqualTo("OwnerController");
        assertThat(unit.kind()).isEqualTo("class");
        assertThat(unit.annotations()).containsExactlyInAnyOrder("@RestController", "@Deprecated");
        assertThat(unit.filePath()).isEqualTo("src/main/java/com/example/OwnerController.java");
    }

    @Test
    @DisplayName("interface / enum / record / @interface 的 kind 各自正确")
    void extractsKindForEachTypeForm() throws IOException {
        write("src/main/java/com/example/Kinds.java", """
                package com.example;
                interface AnInterface {}
                enum AnEnum { A }
                record ARecord(int x) {}
                @interface AnAnnotation {}
                class AClass {}
                """);

        AnalyzeResult result = analyze("src/main/java/com/example/Kinds.java");

        assertThat(result.codeUnits()).extracting(CodeUnitInfo::name, CodeUnitInfo::kind)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("AnInterface", "interface"),
                        org.assertj.core.groups.Tuple.tuple("AnEnum", "enum"),
                        org.assertj.core.groups.Tuple.tuple("ARecord", "record"),
                        org.assertj.core.groups.Tuple.tuple("AnAnnotation", "annotation"),
                        org.assertj.core.groups.Tuple.tuple("AClass", "class"));
    }

    @Test
    @DisplayName("字段含名字、类型与注解；方法含签名与注解；构造方法也算方法")
    void extractsFieldsAndMethods() throws IOException {
        write("src/main/java/com/example/OwnerController.java", """
                package com.example;
                import java.util.List;

                public class OwnerController {
                    @Autowired
                    private OwnerRepository ownerRepository;
                    private List<String> names;

                    @GetMapping("/owners")
                    public String processFindForm(String name) { return name; }

                    public OwnerController(OwnerRepository repo) { this.ownerRepository = repo; }
                }
                class OwnerRepository {}
                """);

        AnalyzeResult result = analyze("src/main/java/com/example/OwnerController.java");

        CodeUnitInfo unit = result.codeUnits().stream()
                .filter(u -> u.name().equals("OwnerController")).findFirst().orElseThrow();
        assertThat(unit.fields()).extracting(f -> f.name() + ":" + f.type())
                .containsExactly("ownerRepository:OwnerRepository", "names:List<String>");
        assertThat(unit.fields().get(0).annotations()).containsExactly("@Autowired");

        List<MethodInfo> ownerMethods = result.methods().stream()
                .filter(m -> m.codeUnitId().equals(unit.id())).toList();
        assertThat(ownerMethods).extracting(MethodInfo::name)
                .containsExactlyInAnyOrder("processFindForm", "OwnerController");
        assertThat(ownerMethods.stream().filter(m -> m.name().equals("processFindForm")).findFirst()
                .orElseThrow().annotations()).containsExactly("@GetMapping");
        assertThat(ownerMethods).allSatisfy(m -> assertThat(m.signature()).isNotBlank());
    }

    @Test
    @DisplayName("行号是 1-based 闭区间——用文件真实行号反查，而不是断言手写常量")
    void lineNumbersAreOneBased() throws IOException {
        String source = """
                package com.example;
                // 第 2 行
                public class Positioned {
                    // 第 4 行
                    void run() { }
                }
                """;
        write("src/main/java/com/example/Positioned.java", source);

        AnalyzeResult result = analyze("src/main/java/com/example/Positioned.java");

        CodeUnitInfo unit = result.codeUnits().get(0);
        List<String> lines = Files.readAllLines(repoRoot.resolve(unit.filePath()));
        assertThat(lines.get(unit.startLine() - 1))
                .as("startLine 指向的那一行应当就是类声明；若实现按 0-based 处理，这里会读到上一行")
                .contains("class Positioned");
        assertThat(lines.get(unit.endLine() - 1)).contains("}");

        MethodInfo method = result.methods().get(0);
        assertThat(lines.get(method.startLine() - 1)).contains("void run()");
    }

    @Test
    @DisplayName("现代语法（record/sealed/switch 表达式）能解析——默认语言级别 JAVA_11 会让它们全部失败")
    void parsesModernSyntax() throws IOException {
        write("src/main/java/com/example/Modern.java", """
                package com.example;

                public sealed interface Shape permits Circle, Square {}
                record Circle(double radius) implements Shape {}
                record Square(double side) implements Shape {}

                class Classifier {
                    String classify(Object o) {
                        return switch (o) {
                            case Integer i -> "int";
                            case String s -> "str";
                            default -> "other";
                        };
                    }
                }
                """);

        AnalyzeResult result = analyze("src/main/java/com/example/Modern.java");

        assertThat(result.failedFiles())
                .as("record/sealed/switch 若解析失败，说明语言级别没设对")
                .isEmpty();
        assertThat(result.codeUnits()).extracting(CodeUnitInfo::name)
                .containsExactlyInAnyOrder("Shape", "Circle", "Square", "Classifier");
    }

    @Test
    @DisplayName("只取顶层类型：嵌套类不单独成单元")
    void extractsOnlyTopLevelTypes() throws IOException {
        write("src/main/java/com/example/Outer.java", """
                package com.example;

                public class Outer {
                    static class Inner {
                        void hidden() { }
                    }
                    void visible() { }
                }
                """);

        AnalyzeResult result = analyze("src/main/java/com/example/Outer.java");

        assertThat(result.codeUnits()).extracting(CodeUnitInfo::name).containsExactly("Outer");
        assertThat(result.methods()).extracting(MethodInfo::name)
                .as("嵌套类的成员不遍历")
                .containsExactly("visible");
    }

    @Test
    @DisplayName("包名以 AST 为准，目录与包声明不一致时也不被路径带偏")
    void packageComesFromAstNotPath() throws IOException {
        write("src/main/java/com/wrong/Dir.java", """
                package com.right;

                public class Dir {}
                """);

        AnalyzeResult result = analyze("src/main/java/com/wrong/Dir.java");

        assertThat(result.codeUnits().get(0).packageName()).isEqualTo("com.right");
    }

    // ---------- 失败隔离 ----------

    @Test
    @DisplayName("坏文件进 failedFiles，其它文件照常解析——一个坏文件不作废整次分析")
    void brokenFileIsIsolated() throws IOException {
        write("src/main/java/com/example/Good.java", """
                package com.example;
                public class Good {}
                """);
        write("src/main/java/com/example/Broken.java", """
                package com.example;
                public class Broken {
                    void oops( {   // 语法错误
                }
                """);

        AnalyzeResult result = analyze("src/main/java/com/example/Good.java",
                "src/main/java/com/example/Broken.java");

        assertThat(result.codeUnits()).extracting(CodeUnitInfo::name).containsExactly("Good");
        assertThat(result.failedFiles()).hasSize(1);
        assertThat(result.failedFiles().get(0).filePath())
                .as("failedFiles 的路径必须是仓库根相对、/ 分隔，否则前端与检索层对不上")
                .isEqualTo("src/main/java/com/example/Broken.java");
        assertThat(result.failedFiles().get(0).reason()).isNotBlank();
    }

    @Test
    @DisplayName("文件读不到也进 failedFiles，不抛异常")
    void missingFileIsRecordedNotThrown() {
        AnalyzeResult result = analyze("src/main/java/com/example/Ghost.java");

        assertThat(result.codeUnits()).isEmpty();
        assertThat(result.failedFiles()).hasSize(1);
        assertThat(result.failedFiles().get(0).filePath())
                .isEqualTo("src/main/java/com/example/Ghost.java");
    }

    // ---------- 确定性 id ----------

    @Test
    @DisplayName("同一输入两次分析产出完全相同的 id 集合")
    void idsAreDeterministic() throws IOException {
        write("src/main/java/com/example/A.java", "package com.example;\npublic class A {}\n");
        write("src/main/java/com/example/B.java", "package com.example;\npublic class B {}\n");

        AnalyzeResult first = analyze("src/main/java/com/example/A.java", "src/main/java/com/example/B.java");
        AnalyzeResult second = analyze("src/main/java/com/example/A.java", "src/main/java/com/example/B.java");

        assertThat(first.codeUnits()).extracting(CodeUnitInfo::id)
                .containsExactlyElementsOf(second.codeUnits().stream().map(CodeUnitInfo::id).toList());
    }

    @Test
    @DisplayName("多模块下全限定名相同也各有唯一 id——id 必须含 filePath")
    void idsAreUniqueWhenFqnRepeatsAcrossModules() throws IOException {
        write("module-a/src/main/java/com/foo/Application.java",
                "package com.foo;\npublic class Application {}\n");
        write("module-b/src/main/java/com/foo/Application.java",
                "package com.foo;\npublic class Application {}\n");

        AnalyzeResult result = analyze("module-a/src/main/java/com/foo/Application.java",
                "module-b/src/main/java/com/foo/Application.java");

        assertThat(result.codeUnits()).hasSize(2);
        assertThat(result.codeUnits()).extracting(CodeUnitInfo::id).doesNotHaveDuplicates();
    }

    // ---------- 框架识别 ----------

    @Test
    @DisplayName("命中 Spring 标记则 framework = spring")
    void detectsSpringFramework() throws IOException {
        write("src/main/java/com/example/App.java", """
                package com.example;
                @SpringBootApplication
                public class App {}
                """);

        AnalyzeResult result = analyze("src/main/java/com/example/App.java");

        assertThat(result.framework()).isEqualTo("spring");
        assertThat(result.codeUnits().get(0).framework()).isEqualTo("spring");
    }

    @Test
    @DisplayName("无标记时 framework 为空串（纯 Java 仓库没有框架）")
    void frameworkIsEmptyWithoutMarkers() throws IOException {
        write("src/main/java/com/example/Plain.java", """
                package com.example;
                public class Plain {}
                """);

        assertThat(analyze("src/main/java/com/example/Plain.java").framework()).isEmpty();
    }

    @Test
    @DisplayName("多框架同时命中时按命中数取胜，数量相同按框架名字典序——保证确定性")
    void frameworkTieBreakIsDeterministic() throws IOException {
        write("src/main/java/com/example/App.java", """
                package com.example;
                @Zeta
                @Alpha
                public class App {}
                """);
        JavaAnalyzeProperties properties = new JavaAnalyzeProperties();
        properties.setFrameworkMarkers(Map.of(
                "zeta-fw", List.of("@Zeta"),
                "alpha-fw", List.of("@Alpha")));
        List<CodeUnitFileInfo> files = List.of(
                new CodeUnitFileInfo("src/main/java/com/example/App.java", "", "", "java"));

        AnalyzeResult result = new JavaSpringAnalyzer(properties)
                .analyze(new AnalyzeRequest("repo-1", repoRoot, files));

        assertThat(result.framework())
                .as("并列时取字典序在前者，不能依赖 Map 的迭代顺序")
                .isEqualTo("alpha-fw");
    }

    // ---------- 依赖边 ----------

    @Test
    @DisplayName("字段类型产生 field 边；仓外类型（java.util.List）不产生边")
    void createsFieldEdgeAndDropsExternal() throws IOException {
        write("src/main/java/com/example/OwnerController.java", """
                package com.example;
                import java.util.List;

                public class OwnerController {
                    private OwnerRepository repo;
                    private List<String> names;
                }
                """);
        write("src/main/java/com/example/OwnerRepository.java", """
                package com.example;
                public class OwnerRepository {}
                """);

        AnalyzeResult result = analyze("src/main/java/com/example/OwnerController.java",
                "src/main/java/com/example/OwnerRepository.java");

        String controllerId = idOf(result, "OwnerController");
        String repositoryId = idOf(result, "OwnerRepository");
        assertThat(result.dependencies())
                .extracting(DependencyEdge::fromCodeUnitId, DependencyEdge::toCodeUnitId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(controllerId, repositoryId));
        assertThat(result.dependencies().get(0).kind()).isEqualTo("field");
    }

    @Test
    @DisplayName("仓内 import 也能产生边（方法体里用到的类型只有 import 这一个信号）")
    void createsImportEdge() throws IOException {
        write("src/main/java/com/example/User.java", """
                package com.example;
                import com.other.Helper;

                public class User {
                    void go() { Helper.run(); }
                }
                """);
        write("src/main/java/com/other/Helper.java", """
                package com.other;
                public class Helper { static void run() {} }
                """);

        AnalyzeResult result = analyze("src/main/java/com/example/User.java",
                "src/main/java/com/other/Helper.java");

        assertThat(result.dependencies())
                .extracting(DependencyEdge::kind)
                .containsExactly("import");
    }

    @Test
    @DisplayName("仓内自定义注解产生 annotation 边")
    void createsAnnotationEdge() throws IOException {
        write("src/main/java/com/example/User.java", """
                package com.example;

                @Marker
                public class User {}
                """);
        write("src/main/java/com/example/Marker.java", """
                package com.example;
                public @interface Marker {}
                """);

        AnalyzeResult result = analyze("src/main/java/com/example/User.java",
                "src/main/java/com/example/Marker.java");

        assertThat(result.dependencies())
                .extracting(DependencyEdge::kind)
                .containsExactly("annotation");
    }

    @Test
    @DisplayName("同一对类既 import 又是字段类型时只留一条边，kind 取信息量最大的 field")
    void deduplicatesEdgeKeepingHighestPriorityKind() throws IOException {
        write("src/main/java/com/example/User.java", """
                package com.example;
                import com.example.Repo;

                public class User {
                    private Repo repo;
                }
                """);
        write("src/main/java/com/example/Repo.java", """
                package com.example;
                public class Repo {}
                """);

        AnalyzeResult result = analyze("src/main/java/com/example/User.java",
                "src/main/java/com/example/Repo.java");

        assertThat(result.dependencies())
                .as("同一对类画两条箭头会让 Mermaid 图看起来是坏的")
                .hasSize(1);
        assertThat(result.dependencies().get(0).kind()).isEqualTo("field");
    }

    @Test
    @DisplayName("★ 同名不同包：边必须连到 import 指定的那个单元，而不是同名的另一个")
    void resolvesToCorrectUnitWhenSameSimpleNameInTwoPackages() throws IOException {
        write("src/main/java/com/a/Foo.java", "package com.a;\npublic class Foo {}\n");
        write("src/main/java/com/b/Foo.java", "package com.b;\npublic class Foo {}\n");
        write("src/main/java/com/use/User.java", """
                package com.use;
                import com.b.Foo;

                public class User {
                    private Foo foo;
                }
                """);

        AnalyzeResult result = analyze("src/main/java/com/a/Foo.java",
                "src/main/java/com/b/Foo.java", "src/main/java/com/use/User.java");

        String userId = idOf(result, "User");
        String fooBPath = "src/main/java/com/b/Foo.java";
        List<DependencyEdge> fromUser = result.dependencies().stream()
                .filter(edge -> edge.fromCodeUnitId().equals(userId)).toList();

        assertThat(fromUser).hasSize(1);
        String targetId = fromUser.get(0).toCodeUnitId();
        String targetPath = result.codeUnits().stream()
                .filter(u -> u.id().equals(targetId)).findFirst().orElseThrow().filePath();
        assertThat(targetPath)
                .as("按简单名随便挑一个同名类是最典型的连错方式，而且『存在边』这类断言发现不了")
                .isEqualTo(fooBPath);
    }

    @Test
    @DisplayName("边的两端都能在 codeUnits 中找到（引用完整性）")
    void edgeEndpointsResolveToCodeUnits() throws IOException {
        write("src/main/java/com/example/OwnerController.java", """
                package com.example;
                public class OwnerController {
                    private OwnerRepository repo;
                }
                """);
        write("src/main/java/com/example/OwnerRepository.java", """
                package com.example;
                public class OwnerRepository {}
                """);

        AnalyzeResult result = analyze("src/main/java/com/example/OwnerController.java",
                "src/main/java/com/example/OwnerRepository.java");

        List<String> unitIds = result.codeUnits().stream().map(CodeUnitInfo::id).toList();
        assertThat(result.dependencies()).isNotEmpty();
        assertThat(result.dependencies()).allSatisfy(edge -> {
            assertThat(unitIds).contains(edge.fromCodeUnitId());
            assertThat(unitIds).contains(edge.toCodeUnitId());
        });
    }

    private static String idOf(AnalyzeResult result, String name) {
        return result.codeUnits().stream()
                .filter(unit -> unit.name().equals(name))
                .findFirst().orElseThrow()
                .id();
    }
}
