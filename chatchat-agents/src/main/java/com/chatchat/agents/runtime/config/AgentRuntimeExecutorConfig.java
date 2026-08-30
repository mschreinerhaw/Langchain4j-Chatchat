package com.chatchat.agents.runtime.config;

import com.chatchat.agents.runtime.execution.LocalWorkflowRuntime;
import com.chatchat.agents.runtime.workflow.WorkflowRuntime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AgentRuntimeExecutorConfig {

    public static final String AGENT_RUNTIME_EXECUTOR = "agentRuntimeExecutor";

    @Bean(name = AGENT_RUNTIME_EXECUTOR, destroyMethod = "shutdown")
    public Executor agentRuntimeExecutor(AgentRuntimeProperties properties) {
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(properties.queueCapacity());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            properties.corePoolSize(),
            properties.maxPoolSize(),
            properties.keepAliveSeconds(),
            TimeUnit.SECONDS,
            queue,
            new AgentRuntimeThreadFactory(properties.threadNamePrefix()),
            new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowRuntime.class)
    @ConditionalOnProperty(prefix = "chatchat.agent-runtime", name = "workflow-engine",
        havingValue = "local", matchIfMissing = true)
    public WorkflowRuntime workflowRuntime(
        @Qualifier(AGENT_RUNTIME_EXECUTOR) Executor executor,
        AgentRuntimeProperties properties
    ) {
        return new LocalWorkflowRuntime(executor, properties);
    }

    private static final class AgentRuntimeThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger(1);
        private final String prefix;

        private AgentRuntimeThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + sequence.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        }
    }
}
