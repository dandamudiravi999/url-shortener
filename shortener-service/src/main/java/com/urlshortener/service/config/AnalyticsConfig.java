package com.urlshortener.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AnalyticsConfig {

    /**
     * Executor used to record click events off the redirect hot path.
     * Bounded queue + caller-runs policy: under overload we degrade to synchronous
     * recording (slower redirects) rather than dropping events or exhausting memory.
     * When {@code shortener.analytics.async=false} (tests) events are recorded inline.
     */
    @Bean(name = "analyticsExecutor")
    public Executor analyticsExecutor(ShortenerProperties properties) {
        if (!properties.analytics().async()) {
            return Runnable::run;
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("analytics-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10_000);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
