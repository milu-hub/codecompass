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
         */
        private String sourceRoot;

        private List<String> fileExtensions = new ArrayList<>();

        /** 文件名级排除，如 Java 的 package-info.java / module-info.java。 */
        private List<String> excludedFileNames = new ArrayList<>();

        /**
         * 供 T1 派生 git sparse-checkout 模式。
         *
         * 由 {@link #sourceRoot} 派生而不是各配一份，是为了避免"扫描范围"与"检出范围"漂移 ——
         * 两者一旦不一致，T2 会扫出空集合却不报错。
         */
        public String sparseCheckoutPattern() {
            return "**/" + sourceRoot + "/**";
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
    }
}
