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
    public LlmConfigService llmConfigService(LlmProperties properties) {
        return new InMemoryLlmConfigService(properties);
    }

    /**
     * 复用的 RestClient（无状态、超时固定），由 factory 按 config 构造客户端。
     * 带用户自填 key 的请求用它在每次调用时造临时客户端，用完即弃。
     */
    @Bean
    public LlmClientFactory llmClientFactory(JsonMapper jsonMapper, LlmProperties properties) {
        // Boot 4.1 不提供 RestClient.Builder Bean（实测注入失败），这里自己建；
        // String 请求体/响应由 OpenAiCompatibleLlmClientTest 对同一构造路径实测过。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getTimeout().toMillis());
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        return config -> new OpenAiCompatibleLlmClient(jsonMapper, restClient, config);
    }

    /** 单例客户端：学习路线/测验等服务用「服务端默认」配置，不经请求头。 */
    @Bean
    public LlmClient llmClient(LlmClientFactory llmClientFactory, LlmConfigService llmConfigService) {
        return llmClientFactory.create(llmConfigService.getDefault());
    }

    @Bean
    public AnswerService answerService(CodeRetriever retriever, LlmClientFactory llmClientFactory,
                                       LlmConfigService llmConfigService, LlmProperties properties,
                                       JsonMapper jsonMapper, CacheService cacheService,
                                       RateLimiter rateLimiter) {
        return new AnswerService(retriever, llmClientFactory, llmConfigService.getDefault(),
                properties, jsonMapper, cacheService, rateLimiter);
    }
}
