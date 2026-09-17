package com.codecompass.repo;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T1 的 Bean 装配。
 *
 * 看门狗调度器是**共享单例**而不是每次 clone 新建：T11 加了限流后会并发多次分析，
 * 每次都新建线程池必然泄漏。因此 {@link CloneWatchdog} 只取消自己的任务，不关这个池。
 */
@Configuration
@EnableConfigurationProperties({CloneProperties.class, ScanProperties.class})
public class RepoConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService cloneWatchdogScheduler() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "clone-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public DiskUsageMeter diskUsageMeter() {
        return new DiskUsageMeter();
    }

    @Bean
    public GitProcessRunner gitProcessRunner() {
        return new GitProcessRunner();
    }

    @Bean
    public TempWorkspaceManager tempWorkspaceManager(CloneProperties properties) {
        return new TempWorkspaceManager(properties.getTempRoot());
    }

    @Bean
    public SourceFileScanner sourceFileScanner(ScanProperties scanProperties) {
        return new SourceFileScanner(scanProperties);
    }

    @Bean
    public GitRepositoryCloner gitRepositoryCloner(CloneProperties properties,
                                                   ScanProperties scanProperties,
                                                   TempWorkspaceManager tempWorkspaceManager,
                                                   GitProcessRunner gitProcessRunner,
                                                   DiskUsageMeter diskUsageMeter,
                                                   ScheduledExecutorService cloneWatchdogScheduler) {
        return new GitRepositoryCloner(properties, scanProperties, tempWorkspaceManager,
                gitProcessRunner, diskUsageMeter, cloneWatchdogScheduler);
    }
}
