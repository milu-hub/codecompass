package com.codecompass.web.dto;

import java.util.List;

/**
 * 类列表的单元视图 —— 前端只取需要的字段（T7 要求 2），由后端预取。
 *
 * <p>{@code role} 来自 T5 分类器经中立 seam 产出的映射；无角色为 {@code ""}。
 * {@code methods}/{@code fields} 是 T14「选中标识符提问」的原料：前端据此把
 * 源码里点击的行吸附到所在方法/字段，作为提问的行锚点。
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
        int endLine,
        List<MethodView> methods,
        List<FieldView> fields) {

    public record MethodView(String name, String signature, int startLine, int endLine) {
    }

    public record FieldView(String name, String type) {
    }
}
