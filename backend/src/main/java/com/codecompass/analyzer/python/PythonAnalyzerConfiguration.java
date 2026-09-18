package com.codecompass.analyzer.python;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Python 分析器的装配（P5）。
 *
 * <p>与 {@code analyzer/java} 的 {@code JavaAnalyzerConfiguration} 同定位：
 * 把语言特有的 Bean 留在本包，根包 {@code analyzer} 的 {@code AnalyzerConfiguration}
 * 不 import 任何语言实现 —— 角色标注器经 Spring 的 Bean 发现进注册表。
 */
@Configuration
@EnableConfigurationProperties(PythonAnalyzeProperties.class)
public class PythonAnalyzerConfiguration {

    @Bean
    public PythonRoleAnnotator pythonRoleAnnotator(PythonAnalyzeProperties properties) {
        return new PythonRoleAnnotator(properties);
    }
}
