package com.chatchat.runtime.news.temporal.config;

import com.chatchat.runtime.news.temporal.activity.NewsCollectionActivityImpl;
import com.chatchat.runtime.news.collector.schedule.NewsCollectionSchedulePolicy;
import com.chatchat.runtime.news.source.persistence.NewsSourceEntity;
import com.chatchat.runtime.news.source.persistence.NewsSourceRepository;
import com.chatchat.runtime.news.temporal.contract.NewsCollectionWorkflowResult;
import com.chatchat.runtime.news.temporal.schedule.NewsEmbeddedTemporalScheduleCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsTemporalEmbeddedServerTest {

    @Test
    void startsWorkerAndRunsLocallyScheduledWorkflowInsideTheApplicationJvm() {
        NewsCollectionActivityImpl activity = mock(NewsCollectionActivityImpl.class);
        when(activity.collect(7L)).thenReturn(new NewsCollectionWorkflowResult(
            "embedded:test", 7L, "COMPLETED", 1, 1, 0, 0, 0, null));
        new ApplicationContextRunner()
            .withUserConfiguration(NewsTemporalConfiguration.class)
            .withPropertyValues(
                "chatchat.runtime.news.temporal.enabled=true",
                "chatchat.runtime.news.temporal.server-mode=embedded")
            .withBean(NewsCollectionActivityImpl.class, () -> activity)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(TestWorkflowEnvironment.class);
                assertThat(context).doesNotHaveBean(ScheduleClient.class);
                assertThat(context.getBean("newsTemporalWorker", SmartLifecycle.class).isRunning())
                    .isTrue();

                NewsSourceEntity source = new NewsSourceEntity();
                source.setId(7L);
                source.setSourceCode("embedded-test");
                source.setEnabled(true);
                source.setScheduleCron("0 0 0 * * *");
                NewsSourceRepository repository = mock(NewsSourceRepository.class);
                when(repository.findAll()).thenReturn(List.of(source));
                var coordinator = new NewsEmbeddedTemporalScheduleCoordinator(repository,
                    new NewsCollectionSchedulePolicy(new ObjectMapper()),
                    context.getBean(WorkflowClient.class), context.getBean(NewsTemporalProperties.class));

                coordinator.reconcile();

                verify(activity, timeout(5_000)).collect(7L);
                assertThat(context.getBean(WorkflowClient.class)
                    .newUntypedWorkflowStub("chatchat-news-collection-7")
                    .getResult(NewsCollectionWorkflowResult.class).status()).isEqualTo("COMPLETED");
            });
    }
}
