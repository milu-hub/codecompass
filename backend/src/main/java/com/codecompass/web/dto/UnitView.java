package com.codecompass.web.dto;

import java.util.List;

/**
 * 类列表的单元视图 —— 前端只取需要的字段（T7 要求 2），由后端预取。
 *
 * <p>{@code role} 来自 T5 分类器经中立 seam 产出的映射；无角色为 {@code ""}。
 */
public record UnitView(
        String id,
        String filePath,
        String packageName,
        String name,
        String kind,
        String role,
        List<String> annotations,
        int startLine,
        int endLine) {
}
