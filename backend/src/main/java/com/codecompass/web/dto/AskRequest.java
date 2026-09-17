package com.codecompass.web.dto;

/**
 * T10 问答请求。
 *
 * @param question 必填；空白由控制器拦截为 400
 * @param unitId   §S7「点击某个类提问」的锚点；可空，空白归一化为 null
 */
public record AskRequest(String question, String unitId) {
}
