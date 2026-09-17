package com.codecompass.analyzer;

import java.util.List;

/**
 * 一次分析的完整结果，对应 SCHEMA.md 的 {@code AnalyzeResult}。
 *
 * <p>所有列表永不为 null（构造时规范化为不可变空列表）。
 *
 * <p><b>约定：单文件解析失败不使整次分析失败</b> —— 失败的记入 {@link #failedFiles()} 后继续。
 * 真实仓库里一定有解析不了的文件，一个畸形源文件不该让整次分析作废。
 */
public record AnalyzeResult(
        String repositoryId,
        String language,
        String framework,
        List<CodeUnitInfo> codeUnits,
        List<MethodInfo> methods,
        List<DependencyEdge> dependencies,
        List<FailedFile> failedFiles) {

    public AnalyzeResult {
        codeUnits = ModelSupport.immutableCopy(codeUnits);
        methods = ModelSupport.immutableCopy(methods);
        dependencies = ModelSupport.immutableCopy(dependencies);
        failedFiles = ModelSupport.immutableCopy(failedFiles);
    }

    public static AnalyzeResult empty(String repositoryId, String language, String framework) {
        return new AnalyzeResult(repositoryId, language, framework,
                List.of(), List.of(), List.of(), List.of());
    }
}
