package com.codecompass.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 临时工作区的分配与**安全删除**。
 *
 * 这是 T1 唯一的破坏性操作，需求 2 和 3 都点名要求"校验路径"。
 * 测试重点全在拒绝路径上：删错一个目录的代价不可逆。
 */
class TempWorkspaceManagerTest {

    @TempDir
    Path tempRoot;

    private TempWorkspaceManager manager() {
        return new TempWorkspaceManager(tempRoot);
    }

    // ---------- create ----------

    @Test
    @DisplayName("create 在根下建立工作区目录")
    void createMakesDirectoryUnderRoot() {
        Path workspace = manager().create("repo-1");

        assertThat(workspace).exists().isDirectory();
        assertThat(workspace.getParent()).isEqualTo(tempRoot);
    }

    @Test
    @DisplayName("create 对已存在目录先清空再重建（不留上次的残留文件）")
    void createWipesExistingDirectory() throws IOException {
        Path workspace = manager().create("repo-1");
        Files.writeString(workspace.resolve("stale.txt"), "leftover from previous run");

        Path recreated = manager().create("repo-1");

        assertThat(recreated).isEqualTo(workspace);
        assertThat(recreated.resolve("stale.txt")).doesNotExist();
    }

    @Test
    @DisplayName("create 拒绝含 .. 的 id（防止逃出临时根）")
    void createRejectsTraversalId() {
        assertThatThrownBy(() -> manager().create("../escaped"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create 拒绝含路径分隔符的 id")
    void createRejectsIdWithSeparator() {
        assertThatThrownBy(() -> manager().create("a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager().create("a\\b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("create 拒绝空 id")
    void createRejectsBlankId() {
        assertThatThrownBy(() -> manager().create("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- delete：正常路径 ----------

    @Test
    @DisplayName("delete 删除根下的工作区")
    void deleteRemovesWorkspace() throws IOException {
        Path workspace = manager().create("repo-1");
        Files.writeString(workspace.resolve("f.txt"), "x");

        manager().delete(workspace);

        assertThat(workspace).doesNotExist();
    }

    @Test
    @DisplayName("delete 对不存在的路径是空操作，不抛异常（清理路径需可重入）")
    void deleteOnMissingPathIsNoOp() {
        manager().delete(tempRoot.resolve("never-existed"));
    }

    @Test
    @DisplayName("delete 能删除只读文件：git 在 Windows 上把 .git/objects/pack/* 标记为只读")
    void deletesReadOnlyFiles() throws IOException {
        Path workspace = manager().create("repo-readonly");
        Path packDir = Files.createDirectories(workspace.resolve("repo/.git/objects/pack"));
        Path packFile = packDir.resolve("pack-479ead82827e5d31acbe4f6ea922633c431efb89.pack");
        Files.write(packFile, new byte[64]);
        assertThat(packFile.toFile().setReadOnly()).isTrue();

        manager().delete(workspace);

        assertThat(workspace)
                .as("真机克隆 petclinic 后清理失败，就是栽在这些只读 pack 文件上")
                .doesNotExist();
    }

    // ---------- delete：必须拒绝 ----------

    @Test
    @DisplayName("delete 拒绝临时根自身")
    void deleteRejectsRootItself() {
        assertThatThrownBy(() -> manager().delete(tempRoot))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tempRoot).exists();
    }

    @Test
    @DisplayName("delete 拒绝根之外的路径")
    void deleteRejectsPathOutsideRoot() throws IOException {
        Path outside = Files.createTempDirectory("cc-outside");
        try {
            assertThatThrownBy(() -> manager().delete(outside))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(outside).exists();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("delete 拒绝用 .. 规范化后逃出根的路径")
    void deleteRejectsTraversalPath() {
        Path escaped = tempRoot.resolve("..").resolve("whatever");

        assertThatThrownBy(() -> manager().delete(escaped))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("delete 拒绝指向根之外的重解析点（路径看起来在根下，真实位置在根外）")
    void deleteRejectsLinkEscapingRoot() throws IOException {
        Path outside = Files.createTempDirectory("cc-link-target");
        Files.writeString(outside.resolve("precious.txt"), "must survive");
        Path link = tempRoot.resolve("escape-link");
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(createDirectoryLink(link, outside),
                    "当前环境既建不了符号链接也建不了目录联接，跳过");
            try {
                assertThatThrownBy(() -> manager().delete(link))
                        .isInstanceOf(IllegalArgumentException.class);
            } finally {
                // 无论上面断言成功与否，都要确认根外的目标没有被跟着删掉
                assertThat(outside.resolve("precious.txt"))
                        .as("删除动作绝不能跟随重解析点删到根外的目标")
                        .exists();
            }
        } finally {
            deleteLinkQuietly(link);
            deleteQuietly(outside);
        }
    }

    /**
     * 优先建符号链接；Windows 无管理员权限时退回目录联接（junction）。
     * 两者都是能把删除动作引到临时根之外的重解析点，安全含义相同。
     */
    private boolean createDirectoryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            try {
                Process process = new ProcessBuilder(
                        "cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                        .redirectErrorStream(true)
                        .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                        .start();
                return process.waitFor() == 0;
            } catch (IOException | InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null");
    }

    private void deleteLinkQuietly(Path link) {
        try {
            Files.deleteIfExists(link);
        } catch (IOException ignored) {
            // 尽力而为
        }
    }

    private void deleteQuietly(Path dir) {
        try {
            Files.deleteIfExists(dir.resolve("precious.txt"));
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // 尽力而为
        }
    }
}
