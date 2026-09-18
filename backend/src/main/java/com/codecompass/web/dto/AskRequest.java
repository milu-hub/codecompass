package com.codecompass.web.dto;

/**
 * T10 问答请求。T14 加行锚点：选中源码里的标识符（方法/属性/类名）后提问。
 *
 * @param question        必填；空白由控制器拦截为 400
 * @param unitId          §S7「点击某个类提问」的锚点；可空，空白归一化为 null
 * @param anchorStartLine 选中范围起始行（1-based）；与 unitId 配合使用，可空
 * @param anchorEndLine   选中范围结束行；可空（空则等于 anchorStartLine）
 */
public record AskRequest(String question, String unitId,
                         Integer anchorStartLine, Integer anchorEndLine) {
}
