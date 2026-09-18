package com.codecompass.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内存单配置实现：从 LlmProperties（application.yml）读取，只读语义。
 *
 * <p>switch/save/delete 在 MVP 里明确拒绝 —— 客户端是启动时用默认配置构建的单例，
 * 假装支持运行时切换会交付一个悄悄失效的功能（易错点 3 的落地）。
 */
class InMemoryLlmConfigServiceTest {

    private static LlmProperties properties(String provider, String baseUrl, String apiKey, String model) {
        LlmProperties properties = new LlmProperties();
        properties.setProvider(provider);
        properties.setBaseUrl(baseUrl);
        properties.setApiKey(apiKey);
        properties.setModel(model);
        return properties;
    }

    @Test
    @DisplayName("list 恒为单条 default 配置，字段与 yml 绑定一致")
    void listReturnsSingleDefaultConfig() {
        InMemoryLlmConfigService service = new InMemoryLlmConfigService(
                properties("deepseek", "https://api.deepseek.com/v1", "sk-1", "deepseek-chat"));

        assertThat(service.list()).containsExactly(
                new LlmConfig("default", "deepseek", "https://api.deepseek.com/v1",
                        "sk-1", "deepseek-chat", true));
    }

    @Test
    @DisplayName("provider 未显式配置时取默认 openai（元数据标签，不参与路由）")
    void providerDefaultsToOpenai() {
        LlmProperties properties = new LlmProperties();
        InMemoryLlmConfigService service = new InMemoryLlmConfigService(properties);

        assertThat(service.getDefault().provider()).isEqualTo("openai");
        assertThat(service.getDefault().isDefault()).isTrue();
        assertThat(service.getDefault().id()).isEqualTo("default");
    }

    @Test
    @DisplayName("getDefault 与 list 的唯一元素是同一个配置")
    void getDefaultMatchesListedConfig() {
        InMemoryLlmConfigService service = new InMemoryLlmConfigService(new LlmProperties());

        assertThat(service.getDefault()).isEqualTo(service.list().get(0));
    }

    @Test
    @DisplayName("switchDefault 只接受 default：未知 id 抛出明确错误")
    void switchDefaultAcceptsOnlyDefaultId() {
        InMemoryLlmConfigService service = new InMemoryLlmConfigService(new LlmProperties());

        assertThat(service.switchDefault("default")).isSameAs(service.getDefault());
        assertThatThrownBy(() -> service.switchDefault("deepseek"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MVP 单配置");
    }

    @Test
    @DisplayName("save 在 MVP 拒绝：配置以 application.yml 为准")
    void saveIsUnsupported() {
        InMemoryLlmConfigService service = new InMemoryLlmConfigService(new LlmProperties());

        assertThatThrownBy(() -> service.save(
                new LlmConfig("x", "openai", "https://x", "sk", "m", false)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("application.yml");
    }

    @Test
    @DisplayName("delete 在 MVP 拒绝：配置以 application.yml 为准")
    void deleteIsUnsupported() {
        InMemoryLlmConfigService service = new InMemoryLlmConfigService(new LlmProperties());

        assertThatThrownBy(() -> service.delete("default"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("application.yml");
    }

    @Test
    @DisplayName("configured()：api-key 或 base-url 为空即视为未配置（承接 502 语义）")
    void configuredSemantics() {
        assertThat(new LlmConfig("d", "openai", "https://x", "", "m", true).configured()).isFalse();
        assertThat(new LlmConfig("d", "openai", "", "sk", "m", true).configured()).isFalse();
        assertThat(new LlmConfig("d", "openai", "https://x", "sk", "m", true).configured()).isTrue();
    }
}
