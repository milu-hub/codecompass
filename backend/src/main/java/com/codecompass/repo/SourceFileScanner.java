package com.codecompass.repo;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T2：把克隆下来的工作区扫描成待解析的代码单元文件清单。
 *
 * <b>为什么不用 glob</b>：实测 Java 的 {@code PathMatcher} 方言与 git 不一致 ——
 * {@code glob:**}{@code /src/main/java/**} 对 {@code src/main/java/org/foo/Bar.java}
 * 返回 <b>false</b>（Java 的星号星号斜杠要求至少一层目录），而 git 用同一个模式**确实**
 * 能检出该文件（真机克隆 petclinic 已验证）。若直接复用 T1 的模式串交给 Java 匹配，
 * 单模块仓库会静默返回 0 个文件。故改用**源码根片段序列匹配**，不依赖任何 glob 引擎。
 *
 * <b>为什么不读文件</b>：包名完全由路径推导。若为了拿包名去读文件内容解析包声明，
 * 就把某门语言的语法知识塞进了这个本该语言无关的扫描器，T3 的分层当场破功，
 * 还会引入编码、BOM、超大文件等新的失败面。
 */
public class SourceFileScanner {

    private static final Logger log = LoggerFactory.getLogger(SourceFileScanner.class);

    private static final String GIT_DIRECTORY = ".git";
    private static final String SEPARATOR = "/";

    private final ScanProperties properties;

    public SourceFileScanner(ScanProperties properties) {
        this.properties = properties;
    }

    /** 扫描仓库工作区。结果按 {@code relativePath} 排序，保证确定性与缓存 key 稳定。 */
    public List<CodeUnitFileInfo> scan(Path repositoryRoot) {
        if (repositoryRoot == null || !Files.isDirectory(repositoryRoot)) {
            log.warn("仓库工作区不存在或不是目录，跳过扫描：{}", repositoryRoot);
            return List.of();
        }

        List<CodeUnitFileInfo> found = new ArrayList<>();
        try {
            Files.walkFileTree(repositoryRoot, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    if (name != null && GIT_DIRECTORY.equals(name.toString())) {
                        // 版本库内部没有源码，不进 .git 翻文件
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    match(toUnixRelativePath(repositoryRoot, file)).ifPresent(found::add);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("无法访问，跳过：{}（{}）", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("扫描仓库时出错：{}", repositoryRoot, e);
        }

        found.sort(Comparator.comparing(CodeUnitFileInfo::relativePath));
        return List.copyOf(found);
    }

    /** 契约与前端、检索层统一使用 {@code /}，Windows 上的反斜杠必须规范掉。 */
    private static String toUnixRelativePath(Path repositoryRoot, Path file) {
        return repositoryRoot.relativize(file).toString().replace('\\', SEPARATOR.charAt(0));
    }

    private Optional<CodeUnitFileInfo> match(String relativePath) {
        String[] segments = relativePath.split(SEPARATOR);
        String fileName = segments[segments.length - 1];
        List<ScanProperties.SourceSpec> sources = properties.getSources();
        if (sources == null) {
            return Optional.empty();
        }

        for (ScanProperties.SourceSpec source : sources) {
            // 目录级排除按 source 判断（语言隔离）：命中路径任一目录段则跳过该 source。
            // 不能放在 preVisitDirectory 里全局剪枝——那会把 Java 包里名为 test 的目录一并排除
            // （cn.javastack.springboot.test 正是踩中这一点，整包被静默漏扫）。
            if (source.getExcludedDirectoryNames() != null
                    && Arrays.stream(segments).anyMatch(source.getExcludedDirectoryNames()::contains)) {
                continue;
            }
            String unitName = stripExtension(fileName, source.getFileExtensions());
            if (unitName == null) {
                continue;
            }
            if (source.getExcludedFileNames().contains(fileName)) {
                continue;
            }
            // P2：整仓模式（source-root "."，Python 用）。包名从仓库根起算，无源码根约束，
            // 因此顶层的 setup.py（segments.length == 1）也要收下。
            if (source.isWholeRepo()) {
                String packageName = segments.length >= 2
                        ? String.join(".", Arrays.copyOfRange(segments, 0, segments.length - 1))
                        : "";
                return Optional.of(new CodeUnitFileInfo(
                        relativePath, packageName, unitName, source.getLanguage()));
            }
            if (segments.length < 2) {
                continue;
            }
            int packageStart = packageStartIndex(segments, source.getSourceRoot());
            if (packageStart < 0) {
                continue;
            }
            String packageName = String.join(".",
                    Arrays.copyOfRange(segments, packageStart, segments.length - 1));
            return Optional.of(new CodeUnitFileInfo(relativePath, packageName, unitName, source.getLanguage()));
        }
        return Optional.empty();
    }

    /** 命中返回去掉扩展名的文件名，不命中返回 null。 */
    private static String stripExtension(String fileName, List<String> extensions) {
        if (extensions == null) {
            return null;
        }
        for (String extension : extensions) {
            if (extension != null
                    && fileName.length() > extension.length()
                    && fileName.endsWith(extension)) {
                return fileName.substring(0, fileName.length() - extension.length());
            }
        }
        return null;
    }

    /**
     * 定位源码根之后第一个包目录的下标；没命中返回 -1。
     *
     * 按**片段序列**匹配（不是子串查找），并取**最后一次**出现 —— 路径里可能重复出现
     * 该序列（如 {@code outer/src/main/java/inner/src/main/java/...}），取第一次会算错包名。
     * 循环上界保证源码根不会把文件名本身也吃掉。
     */
    private static int packageStartIndex(String[] segments, String sourceRoot) {
        if (sourceRoot == null || sourceRoot.isBlank()) {
            return -1;
        }
        String[] rootSegments = sourceRoot.split(SEPARATOR);
        int found = -1;
        for (int i = 0; i + rootSegments.length <= segments.length - 1; i++) {
            boolean matches = true;
            for (int j = 0; j < rootSegments.length; j++) {
                if (!rootSegments[j].equals(segments[i + j])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                found = i;
            }
        }
        return found < 0 ? -1 : found + rootSegments.length;
    }
}
