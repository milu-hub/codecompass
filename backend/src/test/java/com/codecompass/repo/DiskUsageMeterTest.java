package com.codecompass.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 落盘量测量。替代 `du` 子进程：本机 Windows 无系统自带 du，
 * 只有 IDE 捆绑的 BusyBox 版，靠它会让看门狗静默失效。
 *
 * 口径与 `du -sb` 等价：累加文件表观字节数（不含块占用、不含目录 inode）。
 */
class DiskUsageMeterTest {

    private final DiskUsageMeter meter = new DiskUsageMeter();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("空目录：0 字节 0 文件")
    void emptyDirectoryIsZero() {
        DiskUsageMeter.Usage usage = meter.measure(tempDir);

        assertThat(usage.bytes()).isZero();
        assertThat(usage.fileCount()).isZero();
    }

    @Test
    @DisplayName("递归累加所有层级的文件字节数")
    void sumsBytesRecursively() throws IOException {
        writeFile(tempDir.resolve("a.txt"), 1000);
        Path nested = Files.createDirectories(tempDir.resolve("x/y/z"));
        writeFile(nested.resolve("b.txt"), 2500);

        DiskUsageMeter.Usage usage = meter.measure(tempDir);

        assertThat(usage.bytes()).isEqualTo(3500);
    }

    @Test
    @DisplayName("统计文件数时不含目录本身")
    void countsFilesNotDirectories() throws IOException {
        Files.createDirectories(tempDir.resolve("d1/d2"));
        writeFile(tempDir.resolve("a.txt"), 10);
        writeFile(tempDir.resolve("d1/b.txt"), 10);
        writeFile(tempDir.resolve("d1/d2/c.txt"), 10);

        DiskUsageMeter.Usage usage = meter.measure(tempDir);

        assertThat(usage.fileCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("目录不存在时返回 0，不抛异常（看门狗会在目录尚未建好时开始轮询）")
    void missingDirectoryYieldsZero() {
        DiskUsageMeter.Usage usage = meter.measure(tempDir.resolve("does-not-exist"));

        assertThat(usage.bytes()).isZero();
        assertThat(usage.fileCount()).isZero();
    }

    private void writeFile(Path path, int bytes) throws IOException {
        Files.write(path, new byte[bytes]);
    }
}
