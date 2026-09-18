package com.codecompass.web.dto;

import java.util.List;

/**
 * GET /api/repos/{id}/source 的响应：单个类的源码行。
 *
 * <p>只返回**当前选中类**范围内的行（T7 内存快照的切片），不是仓库源码转储 ——
 * §07「不公开大段源码」指缓存/接口不得整仓库倾倒，单类按需取阅是产品本职
 * （S7 引用跳转验证需要看到被引用的行）。
 *
 * <p>行号是 1-based 的真实文件行号：第 i 行文本对应的行号是 {@code startLine + i}。
 */
public record SourceView(
        String file,
        String language,
        int startLine,
        int endLine,
        List<String> lines) {
}
