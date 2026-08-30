package com.chatchat.runtime.temporal.config;

import com.chatchat.agents.runtime.workflow.WorkflowRuntime;
import com.chatchat.agents.runtime.plan.execution.PlanDagControlPort;
import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionPort;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionPhaseHandler;
import com.chatchat.agents.runtime.plan.execution.ResumableAgentRunExecutor;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.runtime.temporal.adapter.TemporalPlanDagControlPort;
import com.chatchat.runtime.temporal.adapter.TemporalPlanToolExecutionPort;
import com.chatchat.runtime.temporal.core.TemporalWorkflowRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

@Configuration
@EnableConfigurationProperties(TemporalWorkflowProperties.class)
@ConditionalOnProperty(prefix = "chatchat.agent-runtime", name = "workflow-engine",
    havingValue = "temporal")
public class TemporalWorkflowConfiguration {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs temporalWorkflowServiceStubs(TemporalWorkflowProperties properties) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
            .setTarget(properties.target())
            .build());
    }

    @Bean
    public WorkflowClient temporalWorkflowClient(WorkflowServiceStubs serviceStubs,
                                                  TemporalWorkflowProperties properties) {
        return WorkflowClient.newInstance(serviceStubs, WorkflowClientOptions.newBuilder()
            .setNamespace(properties.namespace())
            .build());
    }

    @Bean(destroyMethod = "shutdown")
    public WorkerFactory temporalWorkerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean(destroyMethod = "close")
    public WorkflowRuntime temporalWorkflowRuntime(WorkflowClient client,
                                                    WorkerFactory workerFactory,
                                                    ObjectMapper objectMapper,
                                                    TemporalWorkflowProperties properties,
                                                    ToolRuntimeService toolRuntimeService,
                                                    ObjectProvider<PlanExecutionPhaseHandler> phaseHandler) {
        PlanExecutionPhaseHandler handler = phaseHandler.getIfAvailable();
        if (!(handler instanceof ResumableAgentRunExecutor)) {
            throw new IllegalStateException(
                "Temporal agent-run-v1 requires one business handler implementing both "
                    + "PlanExecutionPhaseHandler and ResumableAgentRunExecutor");
        }
        return new TemporalWorkflowRuntime(
            client, workerFactory, objectMapper, properties, toolRuntimeService,
            handler);
    }

    @Bean
    public PlanToolExecutionPort temporalPlanToolExecutionPort(WorkflowClient client,
                                                               TemporalWorkflowProperties properties,
                                                               ToolRuntimeService toolRuntimeService) {
        return new TemporalPlanToolExecutionPort(client, properties, toolRuntimeService);
    }

    @Bean
    public PlanDagControlPort temporalPlanDagControlPort(WorkflowClient client,
                                                         TemporalWorkflowProperties properties) {
        return new TemporalPlanDagControlPort(client, properties);
    }
}
