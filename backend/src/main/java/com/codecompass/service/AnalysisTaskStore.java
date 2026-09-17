package com.codecompass.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * 内存任务存储（MVP 无 MySQL/Redis，TASKBOOK §07 单实例、重启丢失可接受）。
 *
 * <p>每个任务一个 {@link AtomicReference}，状态迁移一律走 CAS 整体替换 ——
 * 见 {@link AnalysisTaskSnapshot} 的原子发布约束。
 */
public class AnalysisTaskStore {

    private final Map<String, AtomicReference<AnalysisTaskSnapshot>> tasks = new ConcurrentHashMap<>();

    /** 创建 pending 任务并返回 taskId。同 URL 重复提交不去重（T11 缓存的事）。 */
    public String create(String url) {
        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        tasks.put(taskId, new AtomicReference<>(new AnalysisTaskSnapshot(
                taskId, url, AnalysisTaskSnapshot.STATUS_PENDING, null, 0,
                "排队中", null, now, now, null)));
        return taskId;
    }

    public Optional<AnalysisTaskSnapshot> find(String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        AtomicReference<AnalysisTaskSnapshot> reference = tasks.get(taskId);
        return reference == null ? Optional.empty() : Optional.of(reference.get());
    }

    /**
     * 原子替换快照。updater 必须是纯函数 —— {@code updateAndGet} 在竞争时会重试。
     */
    public void update(String taskId, UnaryOperator<AnalysisTaskSnapshot> updater) {
        AtomicReference<AnalysisTaskSnapshot> reference = tasks.get(taskId);
        if (reference == null) {
            throw new IllegalArgumentException("任务不存在：" + taskId);
        }
        reference.updateAndGet(updater);
    }
}
