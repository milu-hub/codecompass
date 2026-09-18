package com.codecompass.service;

/**
 * 一份 LLM 配置（多配置抽象的数据模型）。
 *
 * <p>MVP 只有一条（id 恒为 {@code default}，来自 application.yml）；未来多配置管理
 * 复用本模型，届时 id 由存储层分配。
 *
 * <p><b>apiKey 是明文敏感字段</b>：服务内部（LLM 客户端）需要完整值；但任何日志、
 * 异常信息禁止打印本对象，将来对外暴露的端点必须脱敏 —— 模型本身不区分内外，
 * 纪律在调用方。
 *
 * @param provider  元数据标签（openai / deepseek / …），MVP 不参与路由 —— 协议统一是
 *                  OpenAI 兼容，真正按 provider 分路由是未来多配置版本的事
 */
public record LlmConfig(
        String id,
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        boolean isDefault) {

    /** 承接原 LlmProperties.configured() 语义：key/base-url 任一为空即视为未配置（/ask 返回 502）。 */
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }
}
