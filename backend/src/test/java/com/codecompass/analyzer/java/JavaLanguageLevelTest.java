package com.codecompass.analyzer.java;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.javaparser.ParserConfiguration.LanguageLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 语言级别解析。
 *
 * <p>这是 T4 最贵的一个坑：实测 JavaParser 的**默认** LanguageLevel 是 {@code JAVA_11}，
 * 用它解析 record / sealed / switch 表达式会全部失败。也就是说若不显式设置，
 * 现代 Spring 仓库会整批落进 failedFiles，而症状（"大量文件解析失败"）会被误判成
 * "JavaParser 不好用"或"这些文件有问题"。
 */
class JavaLanguageLevelTest {

    @Test
    @DisplayName("默认级别不低于 JAVA_21——否则 record/sealed/switch 表达式整批解析失败")
    void defaultIsAtLeastJava21() {
        String name = JavaLanguageLevels.highestSupported().name();

        assertThat(name)
                .as("级别名形如 JAVA_<n>；JAVA_1_0 这类旧名不该成为最高级别")
                .matches("JAVA_(\\d+)");
        int version = Integer.parseInt(name.substring("JAVA_".length()));
        assertThat(version)
                .as("默认级别 = " + name + "；若 JavaParser 被降级导致天花板低于 21，这里必须失败而不是悄悄退化")
                .isGreaterThanOrEqualTo(21);
    }

    @Test
    @DisplayName("默认级别不是 PREVIEW——预览版语法不是我们要支持的目标")
    void defaultIsNotPreview() {
        assertThat(JavaLanguageLevels.highestSupported().name()).doesNotContain("PREVIEW");
    }

    @Test
    @DisplayName("未配置时取默认级别")
    void blankConfigurationFallsBackToDefault() {
        assertThat(JavaLanguageLevels.resolve(null)).isEqualTo(JavaLanguageLevels.highestSupported());
        assertThat(JavaLanguageLevels.resolve("  ")).isEqualTo(JavaLanguageLevels.highestSupported());
    }

    @Test
    @DisplayName("配置了级别则用它")
    void configuredLevelWins() {
        assertThat(JavaLanguageLevels.resolve("JAVA_17")).isEqualTo(LanguageLevel.JAVA_17);
    }

    @Test
    @DisplayName("级别名大小写不敏感，方便写配置")
    void configurationIsCaseInsensitive() {
        assertThat(JavaLanguageLevels.resolve("java_17")).isEqualTo(LanguageLevel.JAVA_17);
    }

    @Test
    @DisplayName("非法级别名给出明确错误，并指出可留空用默认")
    void invalidLevelIsRejectedClearly() {
        assertThatThrownBy(() -> JavaLanguageLevels.resolve("JAVA_99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JAVA_99")
                .hasMessageContaining("最高级别");
    }
}
