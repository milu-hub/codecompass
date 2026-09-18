package com.codecompass.analyzer.python;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.DependencyEdge;
import com.codecompass.analyzer.FailedFile;
import com.codecompass.analyzer.LanguageAnalyzer;
import com.codecompass.analyzer.MethodInfo;
import com.codecompass.analyzer.python.PythonSourceParser.SyntaxIssue;
import com.codecompass.analyzer.python.parser.PythonParser;
import com.codecompass.repo.CodeUnitFileInfo;

/**
 * P3/P4：Python 分析器（PYTHON_ANALYZER_PLAN.md §3.3 / §3.4）。
 *
 * <p>本类替换 T12 的 {@code PythonStubAnalyzer}：stub 的使命是证明"加语言只加一个实现"，
 * 现在换成真实现，注册表与业务层一行不改。
 *
 * <p>P3 产出结构（类 / 模块级函数 / 方法 / 字段）；P4 在此基础上产出依赖边（import 矩阵）；
 * P5 识别框架（Django/Flask/FastAPI，见 application.yml 的 analyze.python.*）。
 */
@Component
public class PythonAnalyzer implements LanguageAnalyzer {

    private final PythonAnalyzeProperties properties;

    public PythonAnalyzer(PythonAnalyzeProperties properties) {
        this.properties = properties;
    }

    @Override
    public String language() {
        return "python";
    }

    @Override
    public AnalyzeResult analyze(AnalyzeRequest request) {
        PythonSourceParser parser = new PythonSourceParser();
        PythonStructureExtractor extractor = new PythonStructureExtractor();
        PythonImportResolver importResolver = new PythonImportResolver();

        List<CodeUnitInfo> units = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        List<FailedFile> failedFiles = new ArrayList<>();
        Map<String, List<String>> unitIdsByFile = new HashMap<>();
        Map<String, List<PythonImportResolver.PythonImport>> importsByFile = new HashMap<>();

        for (CodeUnitFileInfo file : request.files()) {
            if (!"python".equals(file.language())) {
                // 混仓时只处理自己的文件；Java 文件归 Java 分析器
                continue;
            }
            String source = read(request.repositoryRoot(), file.relativePath());
            if (source == null) {
                failedFiles.add(new FailedFile(file.relativePath(), "无法读取文件"));
                continue;
            }
            PythonSourceParser.ParseOutcome outcome = parser.parse(source);
            if (!outcome.issues().isEmpty()) {
                // 与 Java 侧同语义：有任何语法问题，该文件进 failedFiles、不产出单元
                failedFiles.add(new FailedFile(file.relativePath(), summarize(outcome.issues())));
                continue;
            }
            PythonStructureExtractor.FileUnits extracted =
                    extractor.extract(request.repositoryId(), file, outcome);
            units.addAll(extracted.units());
            methods.addAll(extracted.methods());

            List<String> ids = extracted.units().stream().map(CodeUnitInfo::id).toList();
            if (!ids.isEmpty()) {
                unitIdsByFile.put(file.relativePath(), ids);
            }
            importsByFile.put(file.relativePath(), importResolver.collect(
                    (PythonParser.File_inputContext) outcome.tree(),
                    outcome.tokens(),
                    PythonModuleNames.modulePath(file.relativePath())));
        }

        units.sort(Comparator.comparing(CodeUnitInfo::id));
        methods.sort(Comparator.comparing(MethodInfo::id));
        List<DependencyEdge> dependencies =
                importResolver.resolve(request.repositoryId(), units, unitIdsByFile, importsByFile);
        String framework = detectFramework(units);
        return new AnalyzeResult(request.repositoryId(), "python", framework, units, methods, dependencies, failedFiles);
    }

    /** 框架识别（P5）：扫全部单元的装饰器，按配置声明顺序取第一个命中者；无则空串。 */
    private String detectFramework(List<CodeUnitInfo> units) {
        for (Map.Entry<String, List<String>> framework : properties.getFrameworkMarkers().entrySet()) {
            for (CodeUnitInfo unit : units) {
                if (unit.annotations() != null
                        && framework.getValue().stream().anyMatch(unit.annotations()::contains)) {
                    return framework.getKey();
                }
            }
        }
        return "";
    }

    private static String read(Path root, String relativePath) {
        try {
            return Files.readString(root.resolve(relativePath));
        } catch (IOException e) {
            return null;
        }
    }

    private static String summarize(List<SyntaxIssue> issues) {
        SyntaxIssue first = issues.get(0);
        String more = issues.size() > 1 ? "（另有 " + (issues.size() - 1) + " 处）" : "";
        return "语法错误：" + first.line() + ":" + first.column() + " " + first.message() + more;
    }
}
