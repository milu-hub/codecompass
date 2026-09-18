package com.codecompass.service;

import java.time.Instant;

/** 阅读进度。status ∈ {unread, reading, done}。 */
public record Progress(String clientId, String repoUrl, String codeUnitId,
                       String status, Instant updatedAt) {
}
