package com.codecompass.service;

import java.time.Instant;

/** 成就视图：全部定义 + 解锁时间（未解锁为 null）。 */
public record AchievementView(String code, String name, String description, Instant unlockedAt) {
}
