package com.codecompass.service;

import java.util.List;

/**
 * MVP 的单配置实现：启动时从 {@link LlmProperties}（application.yml）读取，只读。
 *
 * <p>switch/save/delete 显式拒绝（配置以 application.yml 为准）——客户端是启动时
 * 用默认配置构建的单例，假装支持运行时切换会交付一个悄悄失效的功能；把这三件事
 * 留给未来「多配置 + 客户端按请求解析配置」的版本。
 */
public class InMemoryLlmConfigService implements LlmConfigService {

    public static final String DEFAULT_ID = "default";

    private final LlmConfig config;

    public InMemoryLlmConfigService(LlmProperties properties) {
        this.config = new LlmConfig(
                DEFAULT_ID,
                properties.getProvider(),
                properties.getBaseUrl(),
                properties.getApiKey(),
                properties.getModel(),
                true);
    }

    @Override
    public List<LlmConfig> list() {
        return List.of(config);
    }

    @Override
    public LlmConfig getDefault() {
        return config;
    }

    @Override
    public LlmConfig switchDefault(String id) {
        if (!DEFAULT_ID.equals(id)) {
            throw new IllegalArgumentException(
                    "未知配置 id：" + id + "（MVP 单配置，仅 \"" + DEFAULT_ID + "\"）");
        }
        return config;
    }

    @Override
    public LlmConfig save(LlmConfig config) {
        throw new UnsupportedOperationException(
                "MVP 单配置：配置以 application.yml 为准，运行时保存将在多配置版本提供");
    }

    @Override
    public void delete(String id) {
        throw new UnsupportedOperationException(
                "MVP 单配置：配置以 application.yml 为准，运行时删除将在多配置版本提供");
    }
}
