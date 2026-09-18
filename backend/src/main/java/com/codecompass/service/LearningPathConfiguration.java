package com.codecompass.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

/** F2 装配。 */
@Configuration
public class LearningPathConfiguration {

    @Bean
    public LearningPathService learningPathService(LlmClient llmClient, JsonMapper jsonMapper) {
        return new LearningPathService(llmClient, jsonMapper);
    }
}
