package com.codecompass.analyzer.python;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Python 分析配置（P5）。
 *
 * <p>与 Java 侧的 {@code JavaAnalyzeProperties} 同风格：框架标记与角色标记都放配置，
 * 不硬编码进代码。Python 没有注解，靠装饰器名与模块路径约定来判角色。
 */
@ConfigurationProperties(prefix = "codecompass.analyze.python")
public class PythonAnalyzeProperties {

    /** 框架名 → 装饰器名标记（命中任一即判定为该框架，按声明顺序取第一个）。 */
    private Map<String, List<String>> frameworkMarkers = new LinkedHashMap<>();

    /** 角色 → 装饰器名（如 controller → [app.route, app.get, ...]）。 */
    private Map<String, List<String>> decoratorRoles = new LinkedHashMap<>();

    /** 角色 → 模块路径末段（如 entity → [models]，Django 的 models.py 约定）。 */
    private Map<String, List<String>> moduleRoles = new LinkedHashMap<>();

    public Map<String, List<String>> getFrameworkMarkers() {
        return frameworkMarkers;
    }

    public void setFrameworkMarkers(Map<String, List<String>> frameworkMarkers) {
        this.frameworkMarkers = frameworkMarkers;
    }

    public Map<String, List<String>> getDecoratorRoles() {
        return decoratorRoles;
    }

    public void setDecoratorRoles(Map<String, List<String>> decoratorRoles) {
        this.decoratorRoles = decoratorRoles;
    }

    public Map<String, List<String>> getModuleRoles() {
        return moduleRoles;
    }

    public void setModuleRoles(Map<String, List<String>> moduleRoles) {
        this.moduleRoles = moduleRoles;
    }
}
