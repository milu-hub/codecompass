package com.codecompass.repo;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 扫描配置。**这是多语言架构的"那一份配置"** —— 新增一门语言只在这里追加一项，
 * T1（克隆）与 T2（扫描）的代码都不用改。
 *
 * 扫描器不解释 {@code language} 的含义，只是把它原样带到结果里，因此"不在业务层判断文件语言"
 * 是结构性成立的，而不是靠自觉。
 */
@ConfigurationProperties(prefix = "codecompass.scan")
public class ScanProperties {

    private List<SourceSpec> sources = new ArrayList<>();

    public List<SourceSpec> getSources() {
        return sources;
    }

    public void setSources(List<SourceSpec> sources) {
        this.sources = sources;
    }

    /** 一门语言的源码声明。 */
    public static class SourceSpec {

        /** 语言标签，原样写进结果。扫描器不对它做任何判断。 */
        private String language;

        /**
         * 源码根，形如 {@code src/main/java} 的**路径片段序列**。
         * 出现在路径任意深度都算命中，且同时充当包名推导的基准。
         *
         * <p>特殊值 {@code "."} 表示**整仓**（P2 给 Python 用）：没有统一的源码根约定，
         * 包名一律从仓库根起算；sparse-checkout 相应退化为全量检出（见
         * {@link #sparseCheckoutPattern()}）。
         */
        private String sourceRoot;

        private List<String> fileExtensions = new ArrayList<>();

        /** 文件名级排除，如 Java 的 package-info.java / module-info.java。 */
        private List<String> excludedFileNames = new ArrayList<>();

        /**
         * 目录名级排除（P2 给 Python 用），如 tests / venv / node_modules。
         * 所有声明过该配置的语言共享同一份排除名单 —— 扫描是一次遍历，
         * 某语言要排除的目录对其它语言一并跳过（对 Java 无害：这些目录本来就不会命中 src/main/java）。
         */
        private List<String> excludedDirectoryNames = new ArrayList<>();

        /**
         * 供 T1 派生 git sparse-checkout 模式。
         *
         * 由 {@link #sourceRoot} 派生而不是各配一份，是为了避免"扫描范围"与"检出范围"漂移 ——
         * 两者一旦不一致，T2 会扫出空集合却不报错。
         *
         * <p>整仓（{@code source-root: "."}）没有统一源码根，但扫描只按 {@link #fileExtensions}
         * 挑文件，故稀疏检出也按扩展名派生（Python 的 .py 会派生为"任意深度下所有 .py 文件"的模式），
         * 而不是全量检出。否则多语言配置下，任何仓库（哪怕纯 Java）都会被强制全量检出，
         * 大仓库会撞上 60s 克隆超时（实测 spring-petclinic-microservices 全量检出 > 60s）。
         * 未声明扩展名的整仓语言退回全量（安全兜底）。
         *
         * @return 一条或多条 gitignore 风格模式（非 cone 模式），交给 sparse-checkout set。
         */
        public List<String> sparseCheckoutPatterns() {
            if (isWholeRepo()) {
                if (fileExtensions == null || fileExtensions.isEmpty()) {
                    return List.of("**");
                }
                return fileExtensions.stream()
                        .map(extension -> "**/*" + extension)
                        .toList();
            }
            return List.of("**/" + sourceRoot + "/**");
        }

        /** source-root 为 "." 表示整仓（P2：Python 没有统一源码根约定）。 */
        public boolean isWholeRepo() {
            return ".".equals(sourceRoot);
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public String getSourceRoot() {
            return sourceRoot;
        }

        public void setSourceRoot(String sourceRoot) {
            this.sourceRoot = sourceRoot;
        }

        public List<String> getFileExtensions() {
            return fileExtensions;
        }

        public void setFileExtensions(List<String> fileExtensions) {
            this.fileExtensions = fileExtensions;
        }

        public List<String> getExcludedFileNames() {
            return excludedFileNames;
        }

        public void setExcludedFileNames(List<String> excludedFileNames) {
            this.excludedFileNames = excludedFileNames;
        }

        public List<String> getExcludedDirectoryNames() {
            return excludedDirectoryNames;
        }

        public void setExcludedDirectoryNames(List<String> excludedDirectoryNames) {
            this.excludedDirectoryNames = excludedDirectoryNames;
        }
    }
}
