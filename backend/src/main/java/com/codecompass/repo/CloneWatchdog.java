package com.codecompass.repo;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 克隆期间的落盘看门狗。
 *
 * 周期性测量临时目录的总落盘量（`.git` + 已检出工作区），超过阈值就触发中止回调。
 * 没有它的话，一个快速膨胀的大仓库只会撞上 60 秒超时，用户看到的错误是"超时"，
 * 而真实原因是"仓库过大" —— 错误归因会把人引向网络方向排查。
 *
 * 调度器由外部注入并共享（不是每次 clone 新建线程池）：T11 加了限流后会并发多次分析，
 * 每次都新建线程池必然泄漏。{@link #close()} 只取消本次任务，不关闭共享调度器。
 *
 * 中止回调**只会触发一次**：多次触发会让中止逻辑重入。
 */
public class CloneWatchdog implements AutoCloseable {

    private final DiskUsageMeter meter;
    private final ScheduledExecutorService scheduler;
    private final long thresholdBytes;
    private final Duration interval;
    private final Runnable onExceeded;

    private final AtomicBoolean tripped = new AtomicBoolean(false);

    private volatile DiskUsageMeter.Usage lastUsage = DiskUsageMeter.Usage.EMPTY;
    private volatile ScheduledFuture<?> task;

    public CloneWatchdog(DiskUsageMeter meter,
                         ScheduledExecutorService scheduler,
                         long thresholdBytes,
                         Duration interval,
                         Runnable onExceeded) {
        this.meter = Objects.requireNonNull(meter, "meter 不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler 不能为空");
        this.thresholdBytes = thresholdBytes;
        this.interval = Objects.requireNonNull(interval, "interval 不能为空");
        this.onExceeded = Objects.requireNonNull(onExceeded, "onExceeded 不能为空");
    }

    /** 立即开始轮询（首次测量不等待一个间隔）。 */
    public void start(Path workspace) {
        long periodMillis = Math.max(1L, interval.toMillis());
        task = scheduler.scheduleWithFixedDelay(
                () -> poll(workspace), 0L, periodMillis, TimeUnit.MILLISECONDS);
    }

    public boolean tripped() {
        return tripped.get();
    }

    /** 最近一次观测到的用量，用于把实际体积写进错误信息。 */
    public DiskUsageMeter.Usage lastUsage() {
        return lastUsage;
    }

    @Override
    public void close() {
        cancel();
    }

    private void poll(Path workspace) {
        try {
            DiskUsageMeter.Usage usage = meter.measure(workspace);
            lastUsage = usage;
            if (usage.bytes() > thresholdBytes && tripped.compareAndSet(false, true)) {
                cancel();
                onExceeded.run();
            }
        } catch (RuntimeException e) {
            // 看门狗自己绝不能把克隆搞崩：这一轮测失败就跳过，下一轮再试。
        }
    }

    private void cancel() {
        ScheduledFuture<?> current = task;
        if (current != null) {
            current.cancel(false);
        }
    }
}
