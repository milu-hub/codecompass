package com.codecompass.service;

/**
 * 每日 LLM token 限额。业务层只依赖此接口，内存实现可替换。
 *
 * <p>计数按匿名身份（{@code X-Client-Id} / IP）分桶，窗口为自然日（跨天自动翻转）。
 */
public interface RateLimiter {

    /**
     * 消耗一次额度并返回结果。
     *
     * @param clientKey       匿名身份 key（控制器解析：X-Client-Id → X-Forwarded-For → IP）
     * @param estimatedTokens 本次消耗的 token 估算（调用方按提示词长度估算）
     */
    Consumption consume(String clientKey, long estimatedTokens);

    /**
     * @param allowed    是否放行
     * @param usedToday  该身份今日已消耗（含本次；超限时如实累计，不回滚）
     * @param dailyLimit 每日上限
     */
    record Consumption(boolean allowed, long usedToday, long dailyLimit) {
    }
}
