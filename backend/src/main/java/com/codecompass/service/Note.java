package com.codecompass.service;

import java.time.Instant;

/** 一条笔记（F5）。 */
public record Note(Long id, String clientId, String repoUrl, String codeUnitId,
                   String content, Instant createdAt, Instant updatedAt) {
}
