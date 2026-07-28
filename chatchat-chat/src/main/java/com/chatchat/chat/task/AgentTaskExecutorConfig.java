package com.chatchat.chat.task;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

@Configuration
public class AgentTaskExecutorConfig {

    /**
     * Performs the agent task executor operation.
     *
     * @param properties the properties value
     * @return the operation result
     */
    @Bean(name = "agentTaskExecutor")
    public ThreadPoolTaskExecutor agentTaskExecutor(AgentTaskProperties properties) {
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("agent-task-");
        threadFactory.setDaemon(true);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setThreadFactory(threadFactory);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }

    /**
     * Isolates feedback experience attribution from long-running Agent task workers.
     *
     * @param properties the runtime task properties
     * @return the feedback executor
     */
    @Bean(name = "agentFeedbackExecutor")
    public TaskExecutor agentFeedbackExecutor(AgentTaskProperties properties) {
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("agent-feedback-");
        threadFactory.setDaemon(true);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getFeedbackCorePoolSize());
        executor.setMaxPoolSize(properties.getFeedbackMaxPoolSize());
        executor.setQueueCapacity(properties.getFeedbackQueueCapacity());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setThreadFactory(threadFactory);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
