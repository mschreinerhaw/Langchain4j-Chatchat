package com.chatchat.runtime.temporal;

import com.chatchat.agents.runtime.workflow.WorkflowRuntime;
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
                                                    TemporalWorkflowProperties properties) {
        return new TemporalWorkflowRuntime(client, workerFactory, objectMapper, properties);
    }
}
