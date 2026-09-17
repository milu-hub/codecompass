package com.codecompass.repo;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子进程执行器：超时、进程树终止、输出落盘、stdin 不继承。
 *
 * 用系统自带命令而非 git 来测，保证不碰网络、结果确定。
 * Windows 无 `sleep`，用 `ping -n` 充当延时（`timeout` 命令在 stdin 被重定向时会报错，
 * 不能用）。
 */
class GitProcessRunnerTest {

    private final GitProcessRunner runner = new GitProcessRunner();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("命令成功：退出码 0，输出被捕获")
    void capturesOutputOnSuccess() {
        GitProcessRunner.Outcome outcome = run(echo("hello-codecompass"), Duration.ofSeconds(30));

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.outputTail()).contains("hello-codecompass");
    }

    @Test
    @DisplayName("命令失败：如实返回非 0 退出码，不抛异常")
    void reportsNonZeroExit() {
        GitProcessRunner.Outcome outcome = run(exitWith(3), Duration.ofSeconds(30));

        assertThat(outcome.exitCode()).isEqualTo(3);
        assertThat(outcome.timedOut()).isFalse();
    }

    @Test
    @DisplayName("超时：标记 timedOut 且实际耗时远小于命令本身时长（证明确实被杀掉）")
    void killsProcessOnTimeout() {
        long start = System.nanoTime();
        GitProcessRunner.Outcome outcome = run(sleepSeconds(20), Duration.ofSeconds(2));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(outcome.timedOut()).isTrue();
        assertThat(elapsed)
                .as("命令自身要 20 秒，2 秒超时后必须立刻返回")
                .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("killTree 连子孙进程一起终止，不留下孤儿")
    void killTreeTerminatesDescendants() throws Exception {
        GitProcessRunner.RunningCommand running =
                runner.start(spawnChildAndWait(), Map.of(), tempDir, tempDir.resolve("tree.log"));

        List<ProcessHandle> descendants = awaitDescendants(running.pid(), Duration.ofSeconds(10));
        assertThat(descendants)
                .as("命令应已派生出子进程，否则这个测试没测到东西")
                .isNotEmpty();

        running.killTree();

        for (ProcessHandle descendant : descendants) {
            assertThat(awaitDead(descendant, Duration.ofSeconds(10)))
                    .as("子进程 %d 必须随进程树一起被终止", descendant.pid())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("stdin 不继承：会读 stdin 的命令立刻结束而不是挂住")
    void doesNotHangOnStdinReadingCommand() {
        long start = System.nanoTime();
        run(readStdin(), Duration.ofSeconds(10));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed)
                .as("若 stdin 被继承且无输入，该命令会一直等下去")
                .isLessThan(Duration.ofSeconds(9));
    }

    // ---------- helpers ----------

    private GitProcessRunner.Outcome run(List<String> command, Duration timeout) {
        Path log = tempDir.resolve("out-" + System.nanoTime() + ".log");
        return runner.run(command, Map.of(), tempDir, log, timeout);
    }

    private List<ProcessHandle> awaitDescendants(long pid, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        List<ProcessHandle> found = List.of();
        while (System.nanoTime() < deadline) {
            found = ProcessHandle.of(pid).map(h -> h.descendants().toList()).orElse(List.of());
            if (!found.isEmpty()) {
                return found;
            }
            Thread.sleep(50);
        }
        return found;
    }

    private boolean awaitDead(ProcessHandle handle, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (!handle.isAlive()) {
                return true;
            }
            Thread.sleep(50);
        }
        return !handle.isAlive();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static List<String> echo(String text) {
        return isWindows() ? List.of("cmd", "/c", "echo " + text) : List.of("sh", "-c", "echo " + text);
    }

    private static List<String> exitWith(int code) {
        return isWindows() ? List.of("cmd", "/c", "exit " + code) : List.of("sh", "-c", "exit " + code);
    }

    private static List<String> sleepSeconds(int seconds) {
        // Windows 没有 sleep；ping -n N 大约耗时 N-1 秒
        return isWindows()
                ? List.of("cmd", "/c", "ping -n " + (seconds + 1) + " 127.0.0.1")
                : List.of("sh", "-c", "sleep " + seconds);
    }

    private static List<String> spawnChildAndWait() {
        return isWindows()
                ? List.of("cmd", "/c", "ping -n 30 127.0.0.1")
                : List.of("sh", "-c", "sleep 30 & wait");
    }

    private static List<String> readStdin() {
        return isWindows() ? List.of("cmd", "/c", "more") : List.of("sh", "-c", "cat");
    }
}
