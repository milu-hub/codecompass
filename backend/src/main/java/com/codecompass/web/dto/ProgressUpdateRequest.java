package com.codecompass.web.dto;

/** 进度更新请求。 */
public record ProgressUpdateRequest(String repoUrl, String codeUnitId, String status) {
}
