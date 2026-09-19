package com.chatchat.runtime.news.temporal.config;

import com.chatchat.runtime.news.temporal.activity.NewsCollectionActivityImpl;
import com.chatchat.runtime.news.temporal.workflow.NewsCollectionWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@EnableConfigurationProperties(NewsTemporalProperties.class)
@ConditionalOnProperty(prefix = "chatchat.runtime.news.temporal", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class NewsTemporalConfiguration {
    private static final Logger log = LoggerFactory.getLogger(NewsTemporalConfiguration.class);

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "chatchat.runtime.news.temporal", name = "server-mode",
        havingValue = "external")
    public WorkflowServiceStubs newsTemporalServiceStubs(NewsTemporalProperties properties) {
        var options = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(properties.target())
            .setEnableHttps(properties.isTlsEnabled())
            .build();
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "chatchat.runtime.news.temporal", name = "server-mode",
        havingValue = "embedded", matchIfMissing = true)
    public TestWorkflowEnvironment newsEmbeddedTemporalEnvironment(NewsTemporalProperties properties) {
        var clientOptions = WorkflowClientOptions.newBuilder()
            .setNamespace(properties.namespace())
            .build();
        var environment = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(clientOptions)
            .setUseTimeskipping(false)
            .build());
        log.warn("news_temporal_embedded_server_started namespace={} persistence=memory-only",
            environment.getNamespace());
        return environment;
    }

    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "chatchat.runtime.news.temporal", name = "server-mode",
        havingValue = "embedded", matchIfMissing = true)
    public WorkflowServiceStubs newsEmbeddedTemporalServiceStubs(TestWorkflowEnvironment environment) {
        return environment.getWorkflowServiceStubs();
    }

    @Bean
    public WorkflowClient newsTemporalWorkflowClient(WorkflowServiceStubs service,
                                                       NewsTemporalProperties properties) {
        return WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder()
            .setNamespace(properties.namespace())
            .build());
    }

    @Bean
    @ConditionalOnProperty(prefix = "chatchat.runtime.news.temporal", name = "server-mode",
        havingValue = "external")
    public ScheduleClient newsTemporalScheduleClient(WorkflowServiceStubs service,
                                                       NewsTemporalProperties properties) {
        return ScheduleClient.newInstance(service, ScheduleClientOptions.newBuilder()
            .setNamespace(properties.namespace())
            .build());
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "chatchat.runtime.news.temporal", name = "server-mode",
        havingValue = "external")
    public WorkerFactory newsTemporalWorkerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "chatchat.runtime.news.temporal", name = "server-mode",
        havingValue = "embedded", matchIfMissing = true)
    public WorkerFactory newsEmbeddedTemporalWorkerFactory(TestWorkflowEnvironment environment) {
        return environment.getWorkerFactory();
    }

    @Bean
    public SmartLifecycle newsTemporalWorker(WorkerFactory factory,
                                              NewsCollectionActivityImpl activity,
                                              NewsTemporalProperties properties) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public synchronized void start() {
                if (running) return;
                Worker worker = factory.newWorker(properties.taskQueue(), WorkerOptions.newBuilder()
                    .setMaxConcurrentActivityExecutionSize(properties.maxConcurrentActivities())
                    .build());
                worker.registerWorkflowImplementationTypes(NewsCollectionWorkflowImpl.class);
                worker.registerActivitiesImplementations(activity);
                factory.start();
                running = true;
            }

            @Override
            public synchronized void stop() {
                if (!running) return;
                factory.shutdown();
                running = false;
            }

            @Override
            public boolean isRunning() { return running; }

            @Override
            public boolean isAutoStartup() { return true; }

            @Override
            public int getPhase() { return Integer.MAX_VALUE - 100; }
        };
    }
}
