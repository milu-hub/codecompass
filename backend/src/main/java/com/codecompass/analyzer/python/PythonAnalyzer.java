package com.codecompass.analyzer.python;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.FailedFile;
import com.codecompass.analyzer.LanguageAnalyzer;
import com.codecompass.analyzer.MethodInfo;
import com.codecompass.analyzer.python.PythonSourceParser.SyntaxIssue;
import com.codecompass.repo.CodeUnitFileInfo;

/**
 * P3：Python 结构分析器（PYTHON_ANALYZER_PLAN.md §3.3）。
 *
 * <p>本类替换 T12 的 {@code PythonStubAnalyzer}：stub 的使命是证明"加语言只加一个实现"，
 * 现在换成真实现，注册表与业务层一行不改。
 *
 * <p>阶段边界：依赖边在 P4 才做，本类只产出结构，dependencies 恒为空 ——
 * 有依赖的类会出现在 isolated 列表里（图可用，只是没有边）；框架识别（Django/Flask/FastAPI）在 P5。
 */
@Component
public class PythonAnalyzer implements LanguageAnalyzer {

    @Override
    public String language() {
        return "python";
    }

    @Override
    public AnalyzeResult analyze(AnalyzeRequest request) {
        PythonSourceParser parser = new PythonSourceParser();
        PythonStructureExtractor extractor = new PythonStructureExtractor();

        List<CodeUnitInfo> units = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        List<FailedFile> failedFiles = new ArrayList<>();

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
        }

        units.sort(Comparator.comparing(CodeUnitInfo::id));
        methods.sort(Comparator.comparing(MethodInfo::id));
        return new AnalyzeResult(request.repositoryId(), "python", "", units, methods, List.of(), failedFiles);
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
