package com.codecompass.retrieve;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** T9 的装配。检索器以接口暴露 —— 向量实现替换时业务层不动。 */
@Configuration
@EnableConfigurationProperties(RetrieveProperties.class)
public class RetrieveConfiguration {

    @Bean
    public CodeRetriever codeRetriever(RetrieveProperties properties) {
        return new LexicalCodeRetriever(properties);
    }
}
