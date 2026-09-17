package com.codecompass.analyzer.java;

import java.util.Locale;

import com.github.javaparser.ParserConfiguration.LanguageLevel;

/**
 * 语言级别解析。
 *
 * <p><b>为什么必须有这个类</b>：实测 JavaParser 的默认 LanguageLevel 是 {@code JAVA_11}。
 * 用它解析 record / sealed / switch 表达式会直接抛
 * "Record Declarations are not supported ... starting from 'JAVA_14'"。
 * 也就是说若不显式设置，现代 Spring 仓库会整批落进 failedFiles，
 * 而症状会把人引向"JavaParser 不好用"或"这些文件有问题"的错误方向。
 *
 * <p>默认取**最高非 PREVIEW 级别**而不是硬编码版本号：JavaParser 升级后自动跟进，
 * 且不需要我们在两处维护版本。配套测试断言默认级别 ≥ JAVA_21，防止依赖被降级后
 * 天花板悄悄下沉。
 */
public final class JavaLanguageLevels {

    private JavaLanguageLevels() {
    }

    /** 最高非 PREVIEW 级别。3.28.2 实测为 JAVA_26。 */
    public static LanguageLevel highestSupported() {
        LanguageLevel[] levels = LanguageLevel.values();
        for (int i = levels.length - 1; i >= 0; i--) {
            if (!levels[i].name().contains("PREVIEW")) {
                return levels[i];
            }
        }
        throw new IllegalStateException("JavaParser 未暴露任何非 PREVIEW 语言级别");
    }

    /** 配置值留空则用 {@link #highestSupported()}；无法识别时响亮失败而不是回退到默认。 */
    public static LanguageLevel resolve(String configuredName) {
        if (configuredName == null || configuredName.isBlank()) {
            return highestSupported();
        }
        String wanted = configuredName.trim().toUpperCase(Locale.ROOT);
        for (LanguageLevel level : LanguageLevel.values()) {
            if (level.name().equals(wanted)) {
                return level;
            }
        }
        throw new IllegalArgumentException("无法识别的语言级别：" + configuredName
                + "。请填 JavaParser 的 LanguageLevel 常量名（如 JAVA_21），"
                + "或留空以使用最高级别 " + highestSupported().name());
    }
}
