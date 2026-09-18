package com.codecompass.analyzer.java;

import java.util.ArrayList;
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

    /**
     * 框架名 → 角色 → 核心注解。这是 T5「注解列表可配置」的实质载体。
     *
     * <p>与 {@link #frameworkMarkers} 语义不同，刻意分开：前者回答「这个仓库是不是 Spring」，
     * 后者回答「这个类扮演什么角色」。硬合并会让两件事互相绑架。
     *
     * <p>注解写法宽容：{@code @Controller} / {@code Controller} / {@code @org.x.Controller}
     * 都会被规范化成 {@code @Controller}（与 SCHEMA.md 的写法一致）。
     */
    private Map<String, Map<String, List<String>>> coreAnnotations = new LinkedHashMap<>();

    /**
     * 只作角色、不作框架判定的注解（如 JPA 的 {@code @Entity}、MyBatis 的 {@code @Mapper}）。
     *
     * <p>它们不是框架标记（放进 framework-markers 会让「纯 JPA 仓库」被误判成 Spring），
     * 所以从「core-annotations 未进 framework-markers」的一致性告警里排除。
     */
    private List<String> roleOnlyAnnotations = new ArrayList<>();

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

    public Map<String, Map<String, List<String>>> getCoreAnnotations() {
        return coreAnnotations;
    }

    public void setCoreAnnotations(Map<String, Map<String, List<String>>> coreAnnotations) {
        this.coreAnnotations = coreAnnotations;
    }

    public List<String> getRoleOnlyAnnotations() {
        return roleOnlyAnnotations;
    }

    public void setRoleOnlyAnnotations(List<String> roleOnlyAnnotations) {
        this.roleOnlyAnnotations = roleOnlyAnnotations == null ? new ArrayList<>() : roleOnlyAnnotations;
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
