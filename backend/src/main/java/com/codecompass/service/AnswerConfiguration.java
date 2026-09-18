package com.codecompass.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.codecompass.retrieve.CodeRetriever;

import tools.jackson.databind.json.JsonMapper;

/** T10 装配：LLM 客户端（OpenAI 兼容协议）与问答服务。 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class AnswerConfiguration {

    @Bean
    public LlmClient llmClient(JsonMapper jsonMapper, LlmProperties properties) {
        // Boot 4.1 不提供 RestClient.Builder Bean（实测注入失败），这里自己建；
        // String 请求体/响应由 OpenAiCompatibleLlmClientTest 对同一构造路径实测过。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getTimeout().toMillis());
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        return new OpenAiCompatibleLlmClient(jsonMapper, restClient, properties);
    }

    @Bean
    public AnswerService answerService(CodeRetriever retriever, LlmClient llmClient,
                                       LlmProperties properties, JsonMapper jsonMapper,
                                       CacheService cacheService, RateLimiter rateLimiter) {
        return new AnswerService(retriever, llmClient, properties, jsonMapper,
                cacheService, rateLimiter);
    }
}
