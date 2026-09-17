package com.codecompass.analyzer.java;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Java 分析器的装配。
 *
 * <p>刻意把 {@code @Bean} 放在本包而不是 {@code analyzer} 根包的 {@code AnalyzerConfiguration}：
 * 后者一旦 import 本类，根包就出现了语言特有类，T0 定下的
 * 「{@code analyzer/} 根包只放接口与语言中立 DTO」的物理隔离就破了。
 * 注册表通过 Spring 的 Bean 发现机制拿到实现，不需要在根包里点名。
 */
@Configuration
@EnableConfigurationProperties(JavaAnalyzeProperties.class)
public class JavaAnalyzerConfiguration {

    @Bean
    public JavaSpringAnalyzer javaSpringAnalyzer(JavaAnalyzeProperties properties) {
        return new JavaSpringAnalyzer(properties);
    }

    @Bean
    public CoreAnnotationClassifier coreAnnotationClassifier(JavaAnalyzeProperties properties) {
        return new CoreAnnotationClassifier(properties);
    }
}
