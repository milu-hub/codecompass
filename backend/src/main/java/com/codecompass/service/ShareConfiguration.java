package com.codecompass.service;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.codecompass.persistence.LearningPathRepository;
import com.codecompass.persistence.NoteRepository;
import com.codecompass.persistence.QaHistoryRepository;
import com.codecompass.persistence.ShareSnapshotRepository;

import tools.jackson.databind.json.JsonMapper;

/** F6 装配。 */
@Configuration
@EnableConfigurationProperties(ShareProperties.class)
public class ShareConfiguration {

    @Bean
    public QaHistoryRecorder qaHistoryRecorder(QaHistoryRepository repository,
                                               JsonMapper jsonMapper, Clock clock) {
        return new QaHistoryRecorder(repository, jsonMapper, clock);
    }

    @Bean
    public ShareService shareService(QaHistoryRepository qaHistoryRepository,
                                     LearningPathRepository learningPathRepository,
                                     NoteRepository noteRepository,
                                     JsonMapper jsonMapper, Clock clock, ShareProperties properties) {
        return new ShareService(qaHistoryRepository, learningPathRepository, noteRepository,
                jsonMapper, clock, properties);
    }
}
