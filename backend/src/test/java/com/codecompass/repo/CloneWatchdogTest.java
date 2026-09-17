package com.codecompass.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 落盘看门狗：克隆期间周期性测量临时目录，超阈值即触发中止。
 *
 * 目的是让"快速膨胀的大仓库"显示为「仓库过大或超过资源限制」，
 * 而不是一律撞 60 秒超时、把仓库问题误报成网络问题。
 *
 * 测量用真实的文件与真实的 {@link DiskUsageMeter}，不 mock —— 这里要验的正是
 * "测出来的数会不会触发阈值"。
 */
class CloneWatchdogTest {

    @TempDir
    Path workspace;

    private ScheduledExecutorService scheduler;
    private final DiskUsageMeter meter = new DiskUsageMeter();
    private final AtomicInteger exceededCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "clone-watchdog-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("落盘量超过阈值：触发中止，并记录观测到的用量")
    void tripsWhenUsageExceedsThreshold() throws IOException {
        writeFile(workspace.resolve("big.bin"), 4096);

        try (CloneWatchdog watchdog = watchdog(1024)) {
            watchdog.start(workspace);

            await().atMost(Duration.ofSeconds(5)).until(watchdog::tripped);

            assertThat(exceededCount.get()).isEqualTo(1);
            assertThat(watchdog.lastUsage().bytes()).isGreaterThanOrEqualTo(4096);
        }
    }

    @Test
    @DisplayName("落盘量低于阈值：不触发")
    void doesNotTripBelowThreshold() throws IOException {
        writeFile(workspace.resolve("small.bin"), 128);

        try (CloneWatchdog watchdog = watchdog(1_000_000)) {
            watchdog.start(workspace);
            Thread.sleep(200);

            assertThat(watchdog.tripped()).isFalse();
            assertThat(exceededCount.get()).isZero();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("中止回调只触发一次，不随每次轮询重复触发")
    void firesCallbackOnlyOnce() throws IOException {
        writeFile(workspace.resolve("big.bin"), 4096);

        try (CloneWatchdog watchdog = watchdog(1024)) {
            watchdog.start(workspace);

            await().atMost(Duration.ofSeconds(5)).until(watchdog::tripped);
            Thread.sleep(200);

            assertThat(exceededCount.get())
                    .as("多次触发会让中止逻辑重入")
                    .isEqualTo(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("close 后停止轮询：之后再超限也不再触发")
    void closeStopsPolling() throws IOException {
        CloneWatchdog watchdog = watchdog(1024);
        watchdog.start(workspace);
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        watchdog.close();

        writeFile(workspace.resolve("late.bin"), 8192);
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(exceededCount.get()).isZero();
        assertThat(watchdog.tripped()).isFalse();
    }

    private CloneWatchdog watchdog(long thresholdBytes) {
        return new CloneWatchdog(meter, scheduler, thresholdBytes, Duration.ofMillis(30),
                exceededCount::incrementAndGet);
    }

    private void writeFile(Path path, int bytes) throws IOException {
        Files.write(path, new byte[bytes]);
    }
}
