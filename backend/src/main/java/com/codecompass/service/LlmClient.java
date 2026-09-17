package com.codecompass.service;

/**
 * 语言中立的 LLM 通道。MVP 只有 OpenAI 兼容实现。
 *
 * <p>传两个字符串、收一个字符串 —— 与具体 provider、与解析目标语言都无关。
 */
public interface LlmClient {

    /**
     * @throws LlmException 未配置或调用失败（控制器据此返回 502，绝不 500）
     */
    String complete(String systemPrompt, String userPrompt);
}
