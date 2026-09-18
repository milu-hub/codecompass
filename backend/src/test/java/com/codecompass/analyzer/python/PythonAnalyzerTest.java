package com.codecompass.analyzer.python;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.MethodInfo;
import com.codecompass.repo.CodeUnitFileInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 验收：结构抽取（类 / 模块级函数 / 方法 / 字段 + 行号范围 + 失败文件隔离 + 确定性）。
 *
 * 样本按 P3 黄金样本精神手写，行号人工核对（§4.1：行号是产品核心承诺）。
 */
class PythonAnalyzerTest {

    private static final String MODELS = """
            from dataclasses import dataclass

            @dataclass
            class Point:
                origin = (0, 0)

                def __init__(self, x: int, y: int):
                    self.x = x
                    self.y = y

                def norm_squared(self) -> int:
                    return self.x ** 2 + self.y ** 2
            """;

    private static final String MAIN = """
            import os

            def run():
                return os.getcwd()
            """;

    private static final String BROKEN = """
            def broken(:
                pass
            """;

    @TempDir
    Path repoRoot;

    private AnalyzeResult analyze(List<CodeUnitFileInfo> files) {
        return new PythonAnalyzer(new PythonAnalyzeProperties()).analyze(
                new AnalyzeRequest("repo", repoRoot, files));
    }

    private CodeUnitFileInfo py(String relativePath) {
        return new CodeUnitFileInfo(relativePath, "", "", "python");
    }

    @Test
    @DisplayName("类 + 模块级函数：单元、方法、字段、行号范围全部准确")
    void extractsStructure() throws Exception {
        write("app/models.py", MODELS);
        write("app/main.py", MAIN);

        AnalyzeResult result = analyze(List.of(py("app/models.py"), py("app/main.py")));

        assertThat(result.language()).isEqualTo("python");
        assertThat(result.failedFiles()).isEmpty();

        CodeUnitInfo point = result.codeUnits().stream()
                .filter(u -> u.name().equals("Point")).findFirst().orElseThrow();
        assertThat(point.id()).isEqualTo("repo:app/models.py#app.models.Point");
        assertThat(point.kind()).isEqualTo("class");
        assertThat(point.packageName()).as("单元所在容器 = 模块路径").isEqualTo("app.models");
        assertThat(point.annotations()).containsExactly("dataclass");
        assertThat(point.startLine()).as("装饰器行计入范围").isEqualTo(3);
        assertThat(point.endLine()).as("块最后一行，不含 DEDENT 的下一行").isEqualTo(12);
        assertThat(point.fields()).extracting(f -> f.name())
                .containsExactly("origin", "x", "y");
        assertThat(point.fields().get(0).type()).isEmpty();
        assertThat(point.fields().get(1).type()).as("self.x 赋值推断不出类型").isEmpty();

        MethodInfo init = result.methods().stream()
                .filter(m -> m.codeUnitId().equals(point.id()) && m.name().equals("__init__"))
                .findFirst().orElseThrow();
        assertThat(init.id()).isEqualTo("repo:app/models.py#app.models.Point#__init__");
        assertThat(init.startLine()).isEqualTo(7);
        assertThat(init.endLine()).isEqualTo(9);
        assertThat(init.signature()).isEqualTo("self, x: int, y: int");

        CodeUnitInfo run = result.codeUnits().stream()
                .filter(u -> u.name().equals("run")).findFirst().orElseThrow();
        assertThat(run.kind()).isEqualTo("function");
        assertThat(run.id()).isEqualTo("repo:app/main.py#app.main.run");
        assertThat(run.packageName()).isEqualTo("app.main");
        assertThat(run.startLine()).isEqualTo(3);
        assertThat(run.endLine()).isEqualTo(4);
    }

    @Test
    @DisplayName("语法错误的文件进 failedFiles、不产出单元，其余文件照常")
    void isolatesFailedFiles() throws Exception {
        write("app/models.py", MODELS);
        write("broken.py", BROKEN);

        AnalyzeResult result = analyze(List.of(py("app/models.py"), py("broken.py")));

        assertThat(result.failedFiles()).hasSize(1);
        assertThat(result.failedFiles().get(0).filePath()).isEqualTo("broken.py");
        assertThat(result.failedFiles().get(0).reason()).startsWith("语法错误：1:");
        assertThat(result.codeUnits()).isNotEmpty();   // 好文件不受影响
    }

    @Test
    @DisplayName("同一输入两次产出逐字段一致（可复现是缓存与测试的前提）")
    void isDeterministic() throws Exception {
        write("app/models.py", MODELS);
        write("app/main.py", MAIN);

        AnalyzeResult first = analyze(List.of(py("app/models.py"), py("app/main.py")));
        AnalyzeResult second = analyze(List.of(py("app/models.py"), py("app/main.py")));

        assertThat(second.codeUnits()).containsExactlyElementsOf(first.codeUnits());
        assertThat(second.methods()).containsExactlyElementsOf(first.methods());
    }

    @Test
    @DisplayName("框架识别：装饰器命中 framework-markers → framework 字段（P5）")
    void detectsFrameworkFromDecorators() throws Exception {
        write("app.py", """
                from flask import Flask

                app = Flask(__name__)

                @app.route("/")
                def index():
                    return "hi"
                """);
        PythonAnalyzeProperties properties = new PythonAnalyzeProperties();
        properties.setFrameworkMarkers(new LinkedHashMap<>(Map.of("flask", List.of("app.route", "flask"))));

        AnalyzeResult result = new PythonAnalyzer(properties).analyze(
                new AnalyzeRequest("repo", repoRoot, List.of(py("app.py"))));

        assertThat(result.framework()).isEqualTo("flask");
        assertThat(result.codeUnits()).extracting(CodeUnitInfo::name).containsExactly("index");
    }

    private void write(String relativePath, String content) throws Exception {
        Path target = repoRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
