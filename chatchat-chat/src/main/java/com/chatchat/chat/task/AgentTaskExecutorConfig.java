package com.chatchat.chat.task;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
}
