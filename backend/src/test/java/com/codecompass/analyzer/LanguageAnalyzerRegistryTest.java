package com.codecompass.analyzer;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.codecompass.repo.CodeUnitFileInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 语言分析器注册表。
 *
 * 它是 TASKBOOK §07「新增语言只应新增一个 LanguageAnalyzer 实现与一份配置」与
 * T12「加一个 Python stub 实现，业务层代码不需要修改」的落地机制 ——
 * 没有它，业务层只能直接注入 JavaSpringAnalyzer，多语言架构立刻失守。
 *
 * 因此这里刻意用**假分析器**（包括"克林贡语"这种荒唐语言）来验证：
 * 核心层对任何语言都一视同仁，不存在也永远不需要语言分支。
 */
class LanguageAnalyzerRegistryTest {

    /** 测试专用假分析器。record 的 language() 访问器正好满足接口方法。 */
    private record FakeAnalyzer(String language) implements LanguageAnalyzer {

        @Override
        public AnalyzeResult analyze(AnalyzeRequest request) {
            return new AnalyzeResult(request.repositoryId(), language, "",
                    List.of(), List.of(), List.of(), List.of());
        }
    }

    private static AnalyzeRequest request() {
        return new AnalyzeRequest("repo-1", Path.of("."),
                List.of(new CodeUnitFileInfo("a/b.java", "a", "b", "java")));
    }

    @Test
    @DisplayName("按 language 派发到对应分析器")
    void dispatchesByLanguage() {
        LanguageAnalyzerRegistry registry = new LanguageAnalyzerRegistry(
                List.of(new FakeAnalyzer("java"), new FakeAnalyzer("python")));

        assertThat(registry.forLanguage("java")).get()
                .extracting(LanguageAnalyzer::language).isEqualTo("java");
        assertThat(registry.forLanguage("python")).get()
                .extracting(LanguageAnalyzer::language).isEqualTo("python");
    }

    @Test
    @DisplayName("核心层不认语言：注册一个从没见过的语言也能正常派发（T12 验收的缩影）")
    void dispatchesAnyLanguageWithoutCoreChanges() {
        LanguageAnalyzerRegistry registry = new LanguageAnalyzerRegistry(
                List.of(new FakeAnalyzer("klingon")));

        AnalyzeResult result = registry.forLanguage("klingon").orElseThrow()
                .analyze(request());

        assertThat(registry.supportedLanguages()).containsExactly("klingon");
        assertThat(result.language()).isEqualTo("klingon");
        assertThat(result.repositoryId()).isEqualTo("repo-1");
    }

    @Test
    @DisplayName("两个分析器声明同一语言时启动即失败，而不是随机一个静默生效")
    void rejectsDuplicateLanguage() {
        assertThatThrownBy(() -> new LanguageAnalyzerRegistry(
                List.of(new FakeAnalyzer("java"), new FakeAnalyzer("java"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java");
    }

    @Test
    @DisplayName("分析器声明 null 语言时给出明确错误，而不是让内部 Map 抛出晦涩异常")
    void rejectsNullLanguage() {
        assertThatThrownBy(() -> new LanguageAnalyzerRegistry(List.of(new FakeAnalyzer(null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language");
    }

    @Test
    @DisplayName("未支持的语言返回空 Optional——由调用方报错，而不是这里返回空结果")
    void unknownLanguageYieldsEmpty() {
        LanguageAnalyzerRegistry registry = new LanguageAnalyzerRegistry(
                List.of(new FakeAnalyzer("java")));

        assertThat(registry.forLanguage("cobol")).isEmpty();
    }

    @Test
    @DisplayName("null / 空白语言返回空 Optional，不抛 NPE")
    void nullOrBlankLanguageYieldsEmpty() {
        LanguageAnalyzerRegistry registry = new LanguageAnalyzerRegistry(
                List.of(new FakeAnalyzer("java")));

        assertThat(registry.forLanguage(null)).isEmpty();
        assertThat(registry.forLanguage("   ")).isEmpty();
    }

    @Test
    @DisplayName("没有任何分析器时构造合法（应用启动早期尚未装配实现）")
    void toleratesNoAnalyzers() {
        LanguageAnalyzerRegistry registry = new LanguageAnalyzerRegistry(List.of());

        assertThat(registry.supportedLanguages()).isEmpty();
        assertThat(registry.forLanguage("java")).isEmpty();
    }

    @Test
    @DisplayName("supportedLanguages 暴露全部已注册语言，供诊断与错误信息使用")
    void exposesSupportedLanguages() {
        LanguageAnalyzerRegistry registry = new LanguageAnalyzerRegistry(
                List.of(new FakeAnalyzer("java"), new FakeAnalyzer("python"),
                        new FakeAnalyzer("go")));

        assertThat(registry.supportedLanguages())
                .containsExactlyInAnyOrder("java", "python", "go");
    }
}
