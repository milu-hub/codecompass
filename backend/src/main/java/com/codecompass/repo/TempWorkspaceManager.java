package com.codecompass.repo;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * 临时工作区的分配与安全删除。
 *
 * 这是 T1 唯一的破坏性操作，所以"校验路径"的逻辑刻意只存在于这一个类里 ——
 * 分散在两处迟早会不一致。
 *
 * 校验分两步，顺序不可颠倒：
 * <ol>
 *   <li><b>规范化路径包含性</b>：{@code toAbsolutePath().normalize()} 后必须落在临时根之下。
 *       这一步不依赖路径存在，因此能挡住 {@code ../} 穿越。</li>
 *   <li><b>真实路径包含性</b>：路径存在时用 {@code toRealPath()} 解析符号链接后再次比较，
 *       挡住"路径看着在根下、真实位置在根外"的软链逃逸。只有存在的路径才能解析真实路径，
 *       所以必须在第 1 步之后。</li>
 * </ol>
 */
public class TempWorkspaceManager {

    private final Path tempRoot;

    public TempWorkspaceManager(Path tempRoot) {
        this.tempRoot = Objects.requireNonNull(tempRoot, "tempRoot 不能为空");
    }

    /**
     * 在临时根下建立工作区。目录已存在则先按同样的校验规则删除，避免上次运行的残留文件
     * 混进本次分析结果。
     */
    public Path create(String workspaceId) {
        assertValidWorkspaceId(workspaceId);
        Path root = normalizedRoot();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建临时根目录：" + root, e);
        }

        Path workspace = root.resolve(workspaceId).normalize();
        if (!workspace.getParent().equals(root)) {
            throw new IllegalArgumentException("工作区 id 逃出了临时根：" + workspaceId);
        }

        if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
            delete(workspace);
        }
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建工作区：" + workspace, e);
        }
        return workspace;
    }

    /**
     * 校验后递归删除工作区。路径不存在时是空操作（清理路径必须可重入）。
     *
     * @throws IllegalArgumentException 路径为空、等于临时根本身、或在临时根之外 ——
     *         这类情况属于攻击或编程错误，必须响亮失败而不是悄悄跳过
     */
    public void delete(Path workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("待删除路径不能为空");
        }
        Path root = normalizedRoot();
        Path normalized = workspace.toAbsolutePath().normalize();

        if (normalized.equals(root)) {
            throw new IllegalArgumentException("拒绝删除临时根本身：" + root);
        }
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("拒绝删除临时根之外的路径：" + normalized);
        }
        if (!Files.exists(normalized)) {
            return;
        }
        assertRealPathWithinRoot(normalized, workspace);

        deleteRecursively(normalized);
    }

    public Path tempRoot() {
        return normalizedRoot();
    }

    /** 存在的路径才解析真实路径，防符号链接把删除动作引到根外。 */
    private void assertRealPathWithinRoot(Path normalized, Path original) {
        Path realRoot;
        Path real;
        try {
            realRoot = normalizedRoot().toRealPath();
            real = normalized.toRealPath();
        } catch (IOException e) {
            // 并发消失（轮询期间 git 正在清理）就当它已经不在了
            return;
        }
        if (real.equals(realRoot) || !real.startsWith(realRoot)) {
            throw new IllegalArgumentException(
                    "拒绝删除指向临时根之外的符号链接：" + original + " -> " + real);
        }
    }

    private void assertValidWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("工作区 id 不能为空");
        }
        if (workspaceId.contains("/") || workspaceId.contains("\\")) {
            throw new IllegalArgumentException("工作区 id 不能含路径分隔符：" + workspaceId);
        }
        if (workspaceId.equals(".") || workspaceId.equals("..")) {
            throw new IllegalArgumentException("工作区 id 不能是相对路径记号：" + workspaceId);
        }
    }

    private Path normalizedRoot() {
        return tempRoot.toAbsolutePath().normalize();
    }

    /**
     * 递归删除，不跟随符号链接（{@code walkFileTree} 默认行为）——
     * 工作区内部的软链只删链接本身，不会删到链接指向的目标。
     */
    private void deleteRecursively(Path path) {
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    deleteClearingReadOnly(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    deleteClearingReadOnly(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("删除工作区失败：" + path, e);
        }
    }

    /**
     * 删除前清掉只读位。
     *
     * git 在 Windows 上会把 {@code .git/objects/pack/*.pack|.idx|.rev} 设为只读
     * （真机克隆 petclinic 后实测 9 个只读文件），而 {@code Files.delete} 在 Windows 上
     * 拒绝删除只读文件 —— 结果是克隆成功、清理却失败，临时目录永久残留。
     */
    private static void deleteClearingReadOnly(Path path) throws IOException {
        try {
            Files.delete(path);
        } catch (AccessDeniedException e) {
            if (!path.toFile().setWritable(true)) {
                throw e;
            }
            Files.delete(path);
        }
    }
}
