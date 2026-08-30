package com.chatchat.runtime.temporal.config;

import com.chatchat.agents.runtime.workflow.WorkflowRuntime;
import com.chatchat.agents.runtime.plan.execution.PlanDagControlPort;
import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionPort;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.runtime.temporal.adapter.TemporalPlanDagControlPort;
import com.chatchat.runtime.temporal.adapter.TemporalPlanToolExecutionPort;
import com.chatchat.runtime.temporal.core.TemporalWorkflowRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
                assertThat(context).hasSingleBean(PlanToolExecutionPort.class);
                assertThat(context.getBean(PlanToolExecutionPort.class))
                    .isInstanceOf(TemporalPlanToolExecutionPort.class);
                assertThat(context).hasSingleBean(PlanDagControlPort.class);
                assertThat(context.getBean(PlanDagControlPort.class))
                    .isInstanceOf(TemporalPlanDagControlPort.class);
            });
    }

    @Test
    void localSelectionDoesNotInstallTemporalBeans() {
        contextRunner
            .withPropertyValues("chatchat.agent-runtime.workflow-engine=local")
            .run(context -> {
                assertThat(context).doesNotHaveBean(WorkflowRuntime.class);
                assertThat(context).doesNotHaveBean(PlanToolExecutionPort.class);
                assertThat(context).doesNotHaveBean(PlanDagControlPort.class);
                assertThat(context).doesNotHaveBean(TemporalWorkflowProperties.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class JsonConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ToolRuntimeService toolRuntimeService() {
            return mock(ToolRuntimeService.class);
        }
    }
}
