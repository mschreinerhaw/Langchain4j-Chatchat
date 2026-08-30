package com.chatchat.runtime.temporal.config;

import com.chatchat.agents.runtime.workflow.WorkflowRuntime;
import com.chatchat.agents.runtime.plan.execution.PlanDagControlPort;
import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionPort;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionPhaseHandler;
import com.chatchat.agents.runtime.plan.execution.ResumableAgentRunExecutor;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.orchestration.analysis.dispatch.AnalysisDatasetExecutionPort;
import com.chatchat.agents.orchestration.protocol.RuntimeProtocolConfiguration;
import com.chatchat.runtime.temporal.adapter.TemporalPlanDagControlPort;
import com.chatchat.runtime.temporal.adapter.TemporalPlanToolExecutionPort;
import com.chatchat.runtime.temporal.core.TemporalWorkflowRuntime;
import com.chatchat.common.runtime.summary.spi.ModelSummaryDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

class TemporalWorkflowConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(JsonConfiguration.class, RuntimeProtocolConfiguration.class,
            TemporalWorkflowConfiguration.class);

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
                assertThat(context).hasSingleBean(ModelSummaryDispatcher.class);
                assertThat(context.getBean(ModelSummaryDispatcher.class).getClass().getSimpleName())
                    .isEqualTo("TemporalModelSummaryDispatcher");
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

    @Test
    void temporalSelectionFailsFastWithoutResumableBusinessHandler() {
        new ApplicationContextRunner()
            .withUserConfiguration(MissingHandlerConfiguration.class,
                TemporalWorkflowConfiguration.class)
            .withPropertyValues(
                "chatchat.agent-runtime.workflow-engine=temporal",
                "chatchat.agent-runtime.temporal.target=127.0.0.1:7233")
            .run(context -> assertThat(context).hasFailed());
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

        @Bean
        PlanExecutionPhaseHandler planExecutionPhaseHandler() {
            return mock(PlanExecutionPhaseHandler.class,
                withSettings().extraInterfaces(ResumableAgentRunExecutor.class,
                    AnalysisDatasetExecutionPort.class));
        }

        @Bean AgentRuntimeProperties agentRuntimeProperties() { return new AgentRuntimeProperties(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingHandlerConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean ToolRuntimeService toolRuntimeService() { return mock(ToolRuntimeService.class); }
    }
}
