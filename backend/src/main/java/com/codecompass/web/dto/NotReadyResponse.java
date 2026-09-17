package com.codecompass.web.dto;

/** graph 端点对 pending / running 任务的 409 响应。 */
public record NotReadyResponse(String taskId, String status, String error) {
}
