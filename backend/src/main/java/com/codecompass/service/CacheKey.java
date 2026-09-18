package com.codecompass.service;

/**
 * 问答缓存 key —— 要求 1 的四要素。
 *
 * <p>四个组件显式成 record 而非分隔符拼字符串：拼接会让
 * {@code ("a", "b|c")} 与 {@code ("a|b", "c")} 撞 key。
 *
 * @param commitSha    分析时的仓库 HEAD（克隆后 rev-parse 取得）；null 表示未知，
 *                     此时整个缓存不参与 —— 宁可 miss 不可错命中
 * @param anchorFile   锚点单元的 filePath（无锚点或锚点未知为 ""）
 * @param questionHash 问题归一化后的 SHA-256 前 16 位 hex
 * @param model        LLM 模型名（充当模型版本）
 */
public record CacheKey(String commitSha, String anchorFile, String questionHash, String model) {
}
