package com.codecompass.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

/** F4 装配。 */
@Configuration
public class QuizConfiguration {

    @Bean
    public QuizService quizService(LlmClient llmClient, JsonMapper jsonMapper) {
        return new QuizService(llmClient, jsonMapper);
    }
}
