package com.codecompass.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import com.codecompass.persistence.AchievementRepository;
import com.codecompass.persistence.LearningPathRepository;
import com.codecompass.persistence.ProgressRepository;
import com.codecompass.persistence.UserActionRepository;

import tools.jackson.databind.json.JsonMapper;

/** F5 进度 + 成就装配。 */
@Configuration
@EnableConfigurationProperties(AchievementProperties.class)
public class AchievementConfiguration {

    @Bean
    public AchievementService achievementService(UserActionRepository userActions,
                                                 AchievementRepository achievements,
                                                 AchievementProperties properties,
                                                 Clock clock) {
        return new AchievementService(userActions, achievements, properties, clock);
    }

    @Bean
    public ProgressService progressService(ProgressRepository progressRepository,
                                           LearningPathRepository learningPathRepository,
                                           AchievementService achievementService,
                                           JsonMapper jsonMapper,
                                           Clock clock) {
        return new ProgressService(progressRepository, learningPathRepository,
                achievementService, jsonMapper, clock);
    }
}
