package com.codecompass.service;

import java.util.Optional;

/**
 * 问答结果缓存。业务层只依赖此接口，内存实现是可替换的（MVP 无 Redis）。
 *
 * <p>缓存对象只有问答结果（answer + references），**不缓存仓库代码** ——
 * 源码内容在 T7 的内存快照里，不进入缓存层。
 */
public interface CacheService {

    Optional<AnswerResponse> get(CacheKey key);

    void put(CacheKey key, AnswerResponse value);

    /** 当前存活条目数（测试与监控）。 */
    long size();
}
