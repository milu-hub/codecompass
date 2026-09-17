package com.codecompass.graph;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图层的装配。
 *
 * <p>渲染器作为独立 Bean 注入构建器 —— 设计承诺"换渲染器只需换一个 {@link MermaidRenderer}"，
 * 注入让这一点成为可能，而不是把渲染器 new 死在构建器里。
 */
@Configuration
public class GraphConfiguration {

    @Bean
    public MermaidRenderer mermaidRenderer() {
        return new MermaidRenderer();
    }

    @Bean
    public DependencyGraphBuilder dependencyGraphBuilder(MermaidRenderer mermaidRenderer) {
        return new DependencyGraphBuilder(mermaidRenderer);
    }
}
