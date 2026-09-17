package com.codecompass.repo;

/**
 * T2 扫描阶段输出，对应 SCHEMA.md 的 {@code CodeUnitFileInfo}。
 *
 * 此阶段尚未解析注解与依赖，只知道"有哪些文件、属于哪个包、叫什么名字"。
 * {@code unitName} 是语言中立命名 —— Java 下为类名，取自文件名（权威值由 T4 的解析器给出）。
 */
public record CodeUnitFileInfo(String relativePath,
                               String packageName,
                               String unitName,
                               String language) {
}
