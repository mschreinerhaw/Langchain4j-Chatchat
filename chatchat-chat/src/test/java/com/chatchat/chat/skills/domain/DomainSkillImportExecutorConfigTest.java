package com.chatchat.chat.skills.domain;

import com.chatchat.chat.task.core.AgentTaskProperties;
import com.chatchat.chat.task.queue.AgentTaskExecutorConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class DomainSkillImportExecutorConfigTest {
    @Test
    void doesNotBecomeCandidateForAgentThreadPoolInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentTaskProperties.class);
            context.register(AgentTaskExecutorConfig.class, DomainSkillImportExecutorConfig.class);
            context.refresh();

            assertThat(context.getBeanNamesForType(ThreadPoolTaskExecutor.class))
                .contains("agentTaskExecutor")
                .doesNotContain("domainSkillImportExecutor");
            assertThat(context.getBean("domainSkillImportExecutor")).isInstanceOf(TaskExecutor.class)
                .isNotInstanceOf(ThreadPoolTaskExecutor.class);
        }
    }
}
