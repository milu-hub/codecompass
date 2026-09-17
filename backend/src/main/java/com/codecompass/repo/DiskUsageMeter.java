package com.codecompass.repo;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 临时工作区的落盘量测量。
 *
 * 刻意用进程内 NIO 遍历而不是起子进程跑 `du`：开发机是 Windows，
 * `du` 不是系统自带（本机只有 IDE 捆绑的 BusyBox 版，连 `--version` 都不支持），
 * 依赖它会让看门狗在换机器/换 CI 后静默失效 —— 而静默失效的看门狗比没有更危险。
 *
 * 口径与 `du -sb` 一致：累加文件表观字节数，不含块占用与目录 inode。
 */
public class DiskUsageMeter {

    /** 落盘量快照。 */
    public record Usage(long bytes, long fileCount) {

        public static final Usage EMPTY = new Usage(0L, 0L);
    }

    /**
     * 测量目录的落盘量与文件数。目录不存在时返回 {@link Usage#EMPTY} 而不是抛异常 ——
     * 看门狗会在 git 尚未把目录建好时就开始轮询。
     */
    public Usage measure(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Usage.EMPTY;
        }
        UsageAccumulator accumulator = new UsageAccumulator();
        try {
            Files.walkFileTree(root, accumulator);
        } catch (IOException e) {
            // 轮询期间 git 正在并发写入/清理，遍历中途出错属正常；
            // 返回已累计的部分结果，让看门狗下一轮再测，而不是中断整个克隆。
            return accumulator.toUsage();
        }
        return accumulator.toUsage();
    }

    private static final class UsageAccumulator extends SimpleFileVisitor<Path> {

        private long bytes;
        private long fileCount;

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            bytes += attrs.size();
            fileCount++;
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            // 并发删除或句柄未释放（Windows 上常见）会走到这里，跳过该文件即可。
            return FileVisitResult.CONTINUE;
        }

        Usage toUsage() {
            return new Usage(bytes, fileCount);
        }
    }
}
