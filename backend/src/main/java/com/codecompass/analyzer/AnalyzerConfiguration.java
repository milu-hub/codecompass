package com.codecompass.analyzer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T3 的装配。
 *
 * <p>用 {@link ObjectProvider} 而不是直接注入 {@code List<LanguageAnalyzer>}：
 * 后者在**一个实现都没有**时会让容器启动失败，而 T3 阶段正是这种情况
 * （Java 分析器要到 T4 才存在）。这样注册表可以先就位，T4 落地实现时无需改动这里。
 */
@Configuration
public class AnalyzerConfiguration {

    @Bean
    public LanguageAnalyzerRegistry languageAnalyzerRegistry(
            ObjectProvider<LanguageAnalyzer> analyzers) {
        return new LanguageAnalyzerRegistry(analyzers.stream().toList());
    }
}
