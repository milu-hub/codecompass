package com.codecompass.analyzer;

/**
 * 解析失败的文件，对应 SCHEMA.md 中 {@code AnalyzeResult.failedFiles} 的元素。
 *
 * <p>它存在的意义是让"单个文件解析失败"成为一个**可被上层观察到的结构化结果**，
 * 而不是只写一行日志。真实仓库里一定有解析不了的文件，一次分析不能因此整体失败。
 *
 * @param reason 异常摘要，用于前端展示与排查
 */
public record FailedFile(String filePath, String reason) {
}
