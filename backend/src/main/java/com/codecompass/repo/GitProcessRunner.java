package com.codecompass.repo;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 子进程执行器，供 git 命令使用。
 *
 * 三个坑是一起出现的，所以放在同一个类里解决：
 * <ol>
 *   <li><b>管道死锁</b>：git 输出写满 OS 管道缓冲区就会阻塞，而父进程还在等它结束。
 *       这里把 stdout/stderr 直接重定向到文件，从根上不存在缓冲区。</li>
 *   <li><b>交互挂起</b>：访问私有仓库时 git 会停在凭据提示上。stdin 重定向到空设备，
 *       且调用方必须再设 {@code GIT_TERMINAL_PROMPT=0}，两道闸缺一不可。</li>
 *   <li><b>子进程杀不干净</b>：{@code destroyForcibly()} 只杀直接子进程，而 git 会派生
 *       {@code git-remote-https}。所以必须先杀 {@link ProcessHandle#descendants()} 再杀父进程。</li>
 * </ol>
 */
public class GitProcessRunner {

    /** 错误摘要最多带这么多字符的进程输出，避免把整段 clone 进度塞进 errorMessage。 */
    private static final int OUTPUT_TAIL_CHARS = 4000;

    public record Outcome(int exitCode, boolean timedOut, String outputTail) {
    }

    /** 已启动但尚未结束的命令，交给调用方（超时逻辑或看门狗）控制生命周期。 */
    public static final class RunningCommand {

        private final Process process;
        private final Path outputFile;

        private RunningCommand(Process process, Path outputFile) {
            this.process = process;
            this.outputFile = outputFile;
        }

        public long pid() {
            return process.pid();
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        /**
         * 等待结束；超时则终止整棵进程树。
         *
         * @return 退出码与是否超时；超时时退出码无意义
         */
        public Outcome await(Duration timeout) {
            try {
                if (process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return new Outcome(process.exitValue(), false, readTail());
                }
                killTree();
                return new Outcome(process.exitValue(), true, readTail());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                killTree();
                throw new IllegalStateException("等待子进程时被中断", e);
            }
        }

        /** 先杀子孙再杀父进程，并等它们真正退出。 */
        public void killTree() {
            ProcessHandle handle = process.toHandle();
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            awaitExit(process, Duration.ofSeconds(5));
            // 杀父进程的瞬间可能又有新子孙冒出来，再收一次尾
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
        }

        private void awaitExit(Process process, Duration limit) {
            try {
                process.waitFor(limit.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 只取输出尾部：完整输出留在文件里，{@code errorMessage} 不该被 clone 进度刷屏。
         */
        private String readTail() {
            try {
                if (!Files.exists(outputFile)) {
                    return "";
                }
                long size = Files.size(outputFile);
                int toRead = (int) Math.min(size, OUTPUT_TAIL_CHARS);
                byte[] buffer = new byte[toRead];
                try (RandomAccessFile raf = new RandomAccessFile(outputFile.toFile(), "r")) {
                    raf.seek(size - toRead);
                    raf.readFully(buffer);
                }
                return new String(buffer, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                return "";
            }
        }
    }

    public RunningCommand start(List<String> command,
                                Map<String, String> extraEnvironment,
                                Path workingDirectory,
                                Path outputFile) {
        Objects.requireNonNull(command, "command 不能为空");
        try {
            if (outputFile.getParent() != null) {
                Files.createDirectories(outputFile.getParent());
            }
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile());
            builder.environment().putAll(extraEnvironment);
            return new RunningCommand(builder.start(), outputFile);
        } catch (IOException e) {
            throw new IllegalStateException("无法启动命令：" + String.join(" ", command), e);
        }
    }

    public Outcome run(List<String> command,
                       Map<String, String> extraEnvironment,
                       Path workingDirectory,
                       Path outputFile,
                       Duration timeout) {
        return start(command, extraEnvironment, workingDirectory, outputFile).await(timeout);
    }

    private static File nullDevice() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return new File(windows ? "NUL" : "/dev/null");
    }
}
