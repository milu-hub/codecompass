package com.codecompass.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * T1：把 GitHub 公开仓库拉取到临时工作区。
 *
 * 每个仓库一个"作业目录"，内含 git 目标目录 repo 与 clone.log。这样一次删除就能清干净，
 * 且日志不会落在 git 目标目录里（{@code git clone} 要求目标目录为空）。
 *
 * 超时是**整个流程的总预算**，不是每条命令各自的超时 —— 4 条命令各自 60 秒最坏要 240 秒，
 * 用户等不起。
 */
public class GitRepositoryCloner {

    private static final String GITHUB_PREFIX = "https://github.com/";
    private static final String REPO_DIR_NAME = "repo";
    private static final String LOG_FILE_NAME = "clone.log";

    private final CloneProperties properties;
    private final TempWorkspaceManager workspaces;
    private final GitProcessRunner runner;
    private final DiskUsageMeter meter;
    private final ScheduledExecutorService watchdogScheduler;

    public GitRepositoryCloner(CloneProperties properties,
                               TempWorkspaceManager workspaces,
                               GitProcessRunner runner,
                               DiskUsageMeter meter,
                               ScheduledExecutorService watchdogScheduler) {
        this.properties = properties;
        this.workspaces = workspaces;
        this.runner = runner;
        this.meter = meter;
        this.watchdogScheduler = watchdogScheduler;
    }

    public CloneResult clone(String repositoryUrl) {
        Optional<String> urlError = validateRepositoryUrl(repositoryUrl);
        if (urlError.isPresent()) {
            return CloneResult.fail(urlError.get());
        }

        Path jobDir;
        try {
            jobDir = workspaces.create(workspaceIdFor(repositoryUrl));
        } catch (RuntimeException e) {
            return CloneResult.fail("准备工作区失败：" + e.getMessage());
        }

        Path repoDir = jobDir.resolve(REPO_DIR_NAME);
        Path logFile = jobDir.resolve(LOG_FILE_NAME);
        try {
            Files.createDirectories(repoDir);
        } catch (IOException e) {
            return cleanupAndFail(jobDir, "准备工作区失败：" + e.getMessage());
        }

        AtomicReference<GitProcessRunner.RunningCommand> current = new AtomicReference<>();
        AtomicBoolean tooLarge = new AtomicBoolean(false);

        try (CloneWatchdog watchdog = new CloneWatchdog(
                meter,
                watchdogScheduler,
                properties.getLimits().getWatchdogBytes(),
                properties.getLimits().getWatchdogInterval(),
                () -> {
                    tooLarge.set(true);
                    // 与超时走同一条 kill 路径，不另写一套
                    GitProcessRunner.RunningCommand running = current.get();
                    if (running != null) {
                        running.killTree();
                    }
                })) {

            watchdog.start(jobDir);
            long deadline = System.nanoTime() + properties.getTimeout().toNanos();

            for (List<String> command : buildCommands(repoDir, repositoryUrl)) {
                if (tooLarge.get()) {
                    return cleanupAndFail(jobDir, tooLargeMessage(watchdog.lastUsage().bytes()));
                }
                Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
                if (remaining.isZero() || remaining.isNegative()) {
                    return cleanupAndFail(jobDir, timeoutMessage(""));
                }

                GitProcessRunner.RunningCommand running;
                try {
                    running = runner.start(command, gitEnvironment(), repoDir, logFile);
                } catch (RuntimeException e) {
                    return cleanupAndFail(jobDir, "无法执行 git：" + e.getMessage());
                }

                current.set(running);
                GitProcessRunner.Outcome outcome = running.await(remaining);
                current.set(null);

                if (tooLarge.get()) {
                    return cleanupAndFail(jobDir, tooLargeMessage(watchdog.lastUsage().bytes()));
                }
                if (outcome.timedOut()) {
                    return cleanupAndFail(jobDir, timeoutMessage(outcome.outputTail()));
                }
                if (outcome.exitCode() != 0) {
                    return cleanupAndFail(jobDir, gitFailureMessage(outcome));
                }
            }

            Optional<String> limitError = checkLimits(repoDir);
            if (limitError.isPresent()) {
                return cleanupAndFail(jobDir, limitError.get());
            }
            return CloneResult.ok(repoDir.toString());
        }
    }

    // ---------- URL 与工作区 id ----------

    /**
     * 只接受 https 的 github.com 仓库地址。
     *
     * 收紧到单一 host + https 是刻意的：SSH 形式会走密钥与交互提示（与"禁用交互"冲突），
     * 其它协议与 host 都不在本任务范围内。
     */
    Optional<String> validateRepositoryUrl(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            return Optional.of("仓库地址不能为空");
        }
        String url = repositoryUrl.trim();
        if (!url.startsWith(GITHUB_PREFIX)) {
            return Optional.of("只支持 https 的 GitHub 仓库地址，例如 "
                    + GITHUB_PREFIX + "owner/repo，收到：" + url);
        }
        String path = trimSlash(url.substring(GITHUB_PREFIX.length()));
        String[] segments = path.split("/");
        if (segments.length < 2 || segments[0].isBlank() || segments[1].isBlank()) {
            return Optional.of("仓库地址缺少 owner/repo 部分：" + url);
        }
        return Optional.empty();
    }

    /** 工作区 id 必须不含路径分隔符，否则会被 {@link TempWorkspaceManager} 的校验挡下。 */
    String workspaceIdFor(String repositoryUrl) {
        String normalized = normalizeUrl(repositoryUrl);
        int lastSlash = normalized.lastIndexOf('/');
        String name = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        String safeName = name.replaceAll("[^A-Za-z0-9._-]", "-");
        if (safeName.isBlank()) {
            safeName = "repo";
        }
        return safeName + "-" + Integer.toHexString(normalized.hashCode());
    }

    /** 去掉末尾斜杠与 .git 后缀，使同一仓库的不同写法得到同一个 id。 */
    private String normalizeUrl(String repositoryUrl) {
        String url = repositoryUrl == null ? "" : repositoryUrl.trim();
        url = trimSlash(url);
        if (url.toLowerCase(Locale.ROOT).endsWith(".git")) {
            url = url.substring(0, url.length() - ".git".length());
        }
        return trimSlash(url);
    }

    private static String trimSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    // ---------- 命令与受控环境 ----------

    List<List<String>> buildCommands(Path repoDir, String repositoryUrl) {
        List<List<String>> commands = new ArrayList<>();
        commands.add(List.of(gitExecutable(), "-c", "submodule.recurse=false",
                "clone", "--depth", "1", "--filter=blob:none", "--no-checkout",
                repositoryUrl, repoDir.toString()));
        commands.add(gitInRepo(repoDir, "sparse-checkout", "init", "--no-cone"));

        List<String> setPatterns = new ArrayList<>(gitInRepo(repoDir, "sparse-checkout", "set"));
        setPatterns.addAll(properties.getPathPatterns());
        commands.add(List.copyOf(setPatterns));

        commands.add(gitInRepo(repoDir, "checkout"));
        return commands;
    }

    /**
     * 关闭一切交互提示。缺了它，访问私有或不存在的仓库时 git 会停在凭据提示上直到超时。
     * stdin 已在 {@link GitProcessRunner} 里重定向到空设备，这里是第二道闸。
     */
    Map<String, String> gitEnvironment() {
        return Map.of(
                "GIT_TERMINAL_PROMPT", "0",
                "GCM_INTERACTIVE", "Never");
    }

    private List<String> gitInRepo(Path repoDir, String... args) {
        List<String> command = new ArrayList<>();
        command.add(gitExecutable());
        command.add("-C");
        command.add(repoDir.toString());
        command.addAll(List.of(args));
        return command;
    }

    private String gitExecutable() {
        return properties.getGitExecutable();
    }

    // ---------- 终检 ----------

    /** 三项限制按落盘量检查，超限时错误信息必须指明是哪一项。 */
    Optional<String> checkLimits(Path repoDir) {
        CloneProperties.Limits limits = properties.getLimits();
        DiskUsageMeter.Usage usage = meter.measure(repoDir);

        if (usage.bytes() > limits.getMaxRepoBytes()) {
            return Optional.of("仓库超过资源限制：仓库总大小 " + humanReadable(usage.bytes())
                    + "，超过上限 " + humanReadable(limits.getMaxRepoBytes()));
        }
        if (usage.fileCount() > limits.getMaxFiles()) {
            return Optional.of("仓库超过资源限制：文件数 " + usage.fileCount()
                    + "，超过上限 " + limits.getMaxFiles());
        }
        Optional<Path> oversized = findOversizedFile(repoDir, limits.getMaxFileBytes());
        if (oversized.isPresent()) {
            return Optional.of("仓库超过资源限制：单文件 " + repoDir.relativize(oversized.get())
                    + " 大小 " + humanReadable(sizeOf(oversized.get()))
                    + "，超过上限 " + humanReadable(limits.getMaxFileBytes()));
        }
        return Optional.empty();
    }

    private Optional<Path> findOversizedFile(Path repoDir, long maxFileBytes) {
        try (Stream<Path> paths = Files.walk(repoDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> sizeOf(path) > maxFileBytes)
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    // ---------- 错误信息 ----------

    /**
     * 实测：一个**不存在**的仓库，git 要 63.7 秒才返回 "Repository not found"，
     * 已经超过 60 秒硬超时。所以超时文案必须点明可能原因，否则用户会把
     * "仓库不存在"误解成网络问题。
     */
    String timeoutMessage(String gitOutput) {
        String message = "克隆超时（" + properties.getTimeout().toSeconds() + "s）。"
                + "可能原因：仓库不存在、是私有仓库，或网络过慢。";
        return appendGitOutput(message, gitOutput);
    }

    private String tooLargeMessage(long observedBytes) {
        return "仓库过大或超过资源限制：克隆过程中落盘已达 " + humanReadable(observedBytes)
                + "，超过看门狗阈值 " + humanReadable(properties.getLimits().getWatchdogBytes());
    }

    private String gitFailureMessage(GitProcessRunner.Outcome outcome) {
        return appendGitOutput("克隆失败（git 退出码 " + outcome.exitCode() + "）。", outcome.outputTail());
    }

    private static String appendGitOutput(String message, String gitOutput) {
        if (gitOutput == null || gitOutput.isBlank()) {
            return message;
        }
        return message + " git 输出：" + gitOutput;
    }

    private static String humanReadable(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024L * 1024) {
            return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    // ---------- 清理 ----------

    /** 失败路径统一出口：先清理再返回失败，绝不留半成品工作区。 */
    private CloneResult cleanupAndFail(Path jobDir, String message) {
        try {
            workspaces.delete(jobDir);
        } catch (RuntimeException e) {
            // 清理失败不能掩盖真正的失败原因
        }
        return CloneResult.fail(message);
    }
}
