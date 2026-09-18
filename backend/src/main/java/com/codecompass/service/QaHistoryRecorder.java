package com.codecompass.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codecompass.persistence.QaHistoryEntity;
import com.codecompass.persistence.QaHistoryRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * T21：问答历史旁路记录 —— F6 分享快照「前 10 条问答」的数据来源。
 * 写入失败只记 WARN，绝不打断问答主流程。
 */
public class QaHistoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(QaHistoryRecorder.class);

    private final QaHistoryRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public QaHistoryRecorder(QaHistoryRepository repository, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public void record(String clientId, String repoUrl, String commitSha,
                       String question, AnswerResponse answer) {
        try {
            repository.save(new QaHistoryEntity(clientId, repoUrl, commitSha, question,
                    jsonMapper.writeValueAsString(answer), clock.instant()));
        } catch (Exception e) {
            log.warn("问答历史记录失败：{}", e.getMessage());
        }
    }
}
