package com.codecompass.service;

/**
 * 按给定 {@link LlmConfig} 构造一个 {@link LlmClient}。
 *
 * <p>问答链路从「持有固定单例客户端」改为「按请求解析配置」的关键抽象：服务端默认配置在启动时
 * 构造一次单例（供学习路线/测验共用），而带请求头（用户自填 key）的请求则在每次调用时
 * {@link #create(LlmConfig)} 一个临时客户端，<b>用完即弃</b>，不缓存到成员变量。
 */
@FunctionalInterface
public interface LlmClientFactory {

    /** 用给定配置构造客户端。调用方保证 config 非空；是否为「未配置」由客户端内部再判断。 */
    LlmClient create(LlmConfig config);
}
