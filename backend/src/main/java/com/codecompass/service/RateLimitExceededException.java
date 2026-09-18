package com.codecompass.service;

/** 当日 LLM token 额度耗尽。控制器映射为 429。 */
public class RateLimitExceededException extends RuntimeException {

    private final long used;
    private final long limit;

    public RateLimitExceededException(long used, long limit) {
        super("已达今日 LLM token 上限（" + used + "/" + limit + "），请明天再试");
        this.used = used;
        this.limit = limit;
    }

    public long used() {
        return used;
    }

    public long limit() {
        return limit;
    }
}
