package com.chatchat.knowledgebase.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class KnowledgeRuntimeExecutorConfiguration {

    @Bean(name = "knowledgeRuntimeExecutor", destroyMethod = "shutdown")
    ExecutorService knowledgeRuntimeExecutor(
        @Value("${chatchat.knowledge.runtime.max-concurrency:8}") int configuredConcurrency,
        @Value("${chatchat.knowledge.runtime.queue-capacity:64}") int configuredQueueCapacity) {
        int concurrency = Math.max(1, Math.min(32, configuredConcurrency));
        int queueCapacity = Math.max(concurrency, Math.min(1000, configuredQueueCapacity));
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "knowledge-runtime-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }
}
