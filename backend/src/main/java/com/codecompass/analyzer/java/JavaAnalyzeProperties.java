package com.codecompass.analyzer.java;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Java 分析配置。
 *
 * <p>框架识别标记刻意放配置而不是硬编码：T5 明确要求「不在业务层硬编码注解列表（应可配置）」，
 * 现在一次做到位比 T5 再改一遍便宜。
 *
 * <p>YAML 里 {@code @} 是保留指示符，标记必须加引号，例如 {@code - '@Service'}。
 */
@ConfigurationProperties(prefix = "codecompass.analyze")
public class JavaAnalyzeProperties {

    private JavaSettings java = new JavaSettings();

    /** 框架名 → 识别标记。例如 spring → [@SpringBootApplication, @RestController, ...] */
    private Map<String, List<String>> frameworkMarkers = new LinkedHashMap<>();

    public JavaSettings getJava() {
        return java;
    }

    public void setJava(JavaSettings java) {
        this.java = java;
    }

    public Map<String, List<String>> getFrameworkMarkers() {
        return frameworkMarkers;
    }

    public void setFrameworkMarkers(Map<String, List<String>> frameworkMarkers) {
        this.frameworkMarkers = frameworkMarkers;
    }

    public static class JavaSettings {

        /**
         * JavaParser 的 LanguageLevel 常量名，如 {@code JAVA_21}。
         * 留空则取最高非 PREVIEW 级别 —— 默认值刻意不硬编码，避免 JavaParser 升级后需要两处维护。
         */
        private String languageLevel;

        public String getLanguageLevel() {
            return languageLevel;
        }

        public void setLanguageLevel(String languageLevel) {
            this.languageLevel = languageLevel;
        }
    }
}
