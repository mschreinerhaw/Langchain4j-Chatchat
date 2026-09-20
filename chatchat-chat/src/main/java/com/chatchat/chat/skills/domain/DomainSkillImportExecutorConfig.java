package com.chatchat.chat.skills.domain;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
class DomainSkillImportExecutorConfig {
    @Bean(name = "domainSkillImportExecutor")
    TaskExecutor domainSkillImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("domain-skill-import-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return new DomainSkillTaskExecutor(executor);
    }

    /** Keeps the dedicated pool out of unrelated ThreadPoolTaskExecutor autowiring. */
    private static final class DomainSkillTaskExecutor implements TaskExecutor, DisposableBean {
        private final ThreadPoolTaskExecutor delegate;

        private DomainSkillTaskExecutor(ThreadPoolTaskExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable task) {
            delegate.execute(task);
        }

        @Override
        public void destroy() {
            delegate.shutdown();
        }
    }
}
