package com.codecompass.service;

/** 匿名用户（F5 身份）。 */
public record AnonymousUser(String clientId, String nickname, java.time.Instant createdAt) {
}
