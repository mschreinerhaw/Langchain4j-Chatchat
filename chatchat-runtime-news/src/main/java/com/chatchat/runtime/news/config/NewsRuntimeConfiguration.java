package com.chatchat.runtime.news.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class NewsRuntimeConfiguration {
    @Bean("newsCollectorExecutor")
    public Executor newsCollectorExecutor(NewsRuntimeProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int coreSize = Math.max(1, properties.getCollectorCoreSize());
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(Math.max(coreSize, properties.getCollectorMaxSize()));
        executor.setQueueCapacity(Math.max(1, properties.getCollectorQueueCapacity()));
        executor.setThreadNamePrefix("runtime-news-collector-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
