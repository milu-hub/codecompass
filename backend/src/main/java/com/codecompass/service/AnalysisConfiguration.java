package com.codecompass.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.codecompass.analyzer.LanguageAnalyzerRegistry;
import com.codecompass.analyzer.UnitRoleAnnotator;
import com.codecompass.graph.DependencyGraphBuilder;
import com.codecompass.repo.GitRepositoryCloner;
import com.codecompass.repo.SourceFileScanner;
import com.codecompass.repo.TempWorkspaceManager;

/**
 * T7 的装配。
 *
 * <p><b>分析执行池与 T1 的看门狗调度器分开</b>：克隆最长阻塞 60 秒，若与看门狗共用
 * 一个小线程池，一次慢克隆就会把看门狗饿死。
 */
@Configuration
public class AnalysisConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService analysisExecutor() {
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "analysis-pool");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public AnalysisTaskStore analysisTaskStore() {
        return new AnalysisTaskStore();
    }

    @Bean
    public AnalysisOrchestrator analysisOrchestrator(AnalysisTaskStore analysisTaskStore,
                                                    ExecutorService analysisExecutor,
                                                    GitRepositoryCloner gitRepositoryCloner,
                                                    SourceFileScanner sourceFileScanner,
                                                    LanguageAnalyzerRegistry languageAnalyzerRegistry,
                                                    UnitRoleAnnotator unitRoleAnnotator,
                                                    DependencyGraphBuilder dependencyGraphBuilder,
                                                    TempWorkspaceManager tempWorkspaceManager) {
        return new AnalysisOrchestrator(analysisTaskStore, analysisExecutor, gitRepositoryCloner,
                sourceFileScanner, languageAnalyzerRegistry, unitRoleAnnotator,
                dependencyGraphBuilder, tempWorkspaceManager);
    }
}
