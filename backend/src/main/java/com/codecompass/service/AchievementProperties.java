package com.codecompass.service;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 成就定义配置（规则从配置读 —— FEATURE_SPEC 要求）。
 * 解锁判定：user_actions 按 (client, action) 计数 ≥ threshold。
 */
@ConfigurationProperties(prefix = "codecompass.achievements")
public class AchievementProperties {

    private List<Definition> definitions = List.of();

    public List<Definition> getDefinitions() {
        return definitions;
    }

    public void setDefinitions(List<Definition> definitions) {
        this.definitions = definitions == null ? List.of() : definitions;
    }

    public record Definition(String code, String name, String description, String action, long threshold) {
    }
}
