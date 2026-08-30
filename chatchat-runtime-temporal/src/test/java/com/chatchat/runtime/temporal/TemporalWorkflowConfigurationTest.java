package com.chatchat.runtime.temporal;

import com.chatchat.agents.runtime.workflow.WorkflowRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalWorkflowConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(JsonConfiguration.class, TemporalWorkflowConfiguration.class);

    @Test
    void temporalSelectionInstallsDurableWorkflowRuntime() {
        contextRunner
            .withPropertyValues(
                "chatchat.agent-runtime.workflow-engine=temporal",
                "chatchat.agent-runtime.temporal.target=127.0.0.1:7233",
                "chatchat.agent-runtime.temporal.task-queue=test-runtime")
            .run(context -> {
                assertThat(context).hasSingleBean(WorkflowRuntime.class);
                assertThat(context.getBean(WorkflowRuntime.class))
                    .isInstanceOf(TemporalWorkflowRuntime.class);
            });
    }

    @Test
    void localSelectionDoesNotInstallTemporalBeans() {
        contextRunner
            .withPropertyValues("chatchat.agent-runtime.workflow-engine=local")
            .run(context -> {
                assertThat(context).doesNotHaveBean(WorkflowRuntime.class);
                assertThat(context).doesNotHaveBean(TemporalWorkflowProperties.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class JsonConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
