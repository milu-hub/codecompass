package com.codecompass.repo;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * T1 克隆相关配置。全部阈值走配置，不硬编码在类里。
 *
 * 路径模式是"多语言扩展点"的一部分：将来支持 Python 只需在配置里追加一条 Python 源码
 * 路径模式，业务层不动。注意本文件注释里不能出现 glob 星号斜杠组合，否则会提前闭合块注释。
 */
@ConfigurationProperties(prefix = "codecompass.clone")
public class CloneProperties {

    /** git 可执行文件。可配置是为了让测试能指向一个不存在的路径来验证失败清理。 */
    private String gitExecutable = "git";

    private Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"), "codecompass");

    /** 整个克隆流程（4 条 git 命令）的总预算，不是每条命令各自的超时。 */
    private Duration timeout = Duration.ofSeconds(60);

    private List<String> pathPatterns = List.of("pom.xml", "**/pom.xml", "**/src/main/java/**");

    private Limits limits = new Limits();

    public String getGitExecutable() {
        return gitExecutable;
    }

    public void setGitExecutable(String gitExecutable) {
        this.gitExecutable = gitExecutable;
    }

    public Path getTempRoot() {
        return tempRoot;
    }

    public void setTempRoot(Path tempRoot) {
        this.tempRoot = tempRoot;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public List<String> getPathPatterns() {
        return pathPatterns;
    }

    public void setPathPatterns(List<String> pathPatterns) {
        this.pathPatterns = pathPatterns;
    }

    public Limits getLimits() {
        return limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits;
    }

    /** 仓库限制。三项均按落盘量计（详见 TASKS.md T1 的口径定义）。 */
    public static class Limits {

        /** 看门狗动态中止阈值：500 MiB 的 1.2 倍，留误杀余量。 */
        private long watchdogBytes = 629_145_600L;

        private Duration watchdogInterval = Duration.ofSeconds(2);

        /** 克隆成功后的终检阈值。 */
        private long maxRepoBytes = 524_288_000L;

        private long maxFileBytes = 20_971_520L;

        private long maxFiles = 1000L;

        public long getWatchdogBytes() {
            return watchdogBytes;
        }

        public void setWatchdogBytes(long watchdogBytes) {
            this.watchdogBytes = watchdogBytes;
        }

        public Duration getWatchdogInterval() {
            return watchdogInterval;
        }

        public void setWatchdogInterval(Duration watchdogInterval) {
            this.watchdogInterval = watchdogInterval;
        }

        public long getMaxRepoBytes() {
            return maxRepoBytes;
        }

        public void setMaxRepoBytes(long maxRepoBytes) {
            this.maxRepoBytes = maxRepoBytes;
        }

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }

        public long getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(long maxFiles) {
            this.maxFiles = maxFiles;
        }
    }
}
