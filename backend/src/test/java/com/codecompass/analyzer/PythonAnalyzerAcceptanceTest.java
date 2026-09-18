package com.codecompass.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多语言扩展点验收（T12 的 stub 已被 P3 的真实 PythonAnalyzer 替换）。
 *
 * <p>两层断言缺一不可：
 * <ul>
 *   <li><b>运行态</b>：Python 分析器经 ObjectProvider 自动进注册表，与 java 并存；</li>
 *   <li><b>结构态</b>：业务层（service/web/repo/graph/retrieve）源码零引用 {@code analyzer.python}
 *       —— 运行时绿不能证明结构没被破坏，加语言必须不碰业务层。</li>
 * </ul>
 */
@SpringBootTest
class PythonAnalyzerAcceptanceTest {

    @Autowired
    private LanguageAnalyzerRegistry registry;

    @Test
    @DisplayName("Python 分析器自动注册：registry 同时提供 java 与 python，空输入返回空结果")
    void pythonAnalyzerIsRegisteredAlongsideJava() {
        LanguageAnalyzer python = registry.forLanguage("python").orElseThrow();
        assertThat(python.language()).isEqualTo("python");
        assertThat(python.getClass().getSimpleName()).isEqualTo("PythonAnalyzer");
        assertThat(registry.supportedLanguages()).containsExactlyInAnyOrder("java", "python");

        AnalyzeResult result = python.analyze(new AnalyzeRequest("repo-1", Path.of("."), List.of()));
        assertThat(result.language()).isEqualTo("python");
        assertThat(result.framework()).isEmpty();
        assertThat(result.codeUnits()).isEmpty();
        assertThat(result.methods()).isEmpty();
        assertThat(result.dependencies()).isEmpty();
        assertThat(result.failedFiles()).isEmpty();
    }

    @Test
    @DisplayName("结构断言：业务层源码对 analyzer.python 零引用（加语言不动业务层的证据）")
    void businessLayersDoNotReferencePythonAnalyzer() throws Exception {
        Path srcMain = Path.of("src/main/java/com/codecompass");
        List<String> businessPackages = List.of("service", "web", "repo", "graph", "retrieve");

        for (String pkg : businessPackages) {
            Path dir = srcMain.resolve(pkg);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String content = Files.readString(file);
                    assertThat(content)
                            .as("业务层文件不得引用 analyzer.python：" + file)
                            .doesNotContain("analyzer.python");
                }
            }
        }
    }
}
