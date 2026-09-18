package com.codecompass.web.dto;

/** 笔记写请求。 */
public record NoteRequest(String repoUrl, String codeUnitId, String content) {
}
