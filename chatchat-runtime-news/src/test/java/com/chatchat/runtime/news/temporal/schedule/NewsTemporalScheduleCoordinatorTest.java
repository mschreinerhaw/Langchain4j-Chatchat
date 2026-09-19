package com.chatchat.runtime.news.temporal.schedule;

import com.chatchat.runtime.news.collector.schedule.NewsCollectionSchedulePolicy;
import com.chatchat.runtime.news.source.persistence.NewsSourceEntity;
import com.chatchat.runtime.news.source.persistence.NewsSourceRepository;
import com.chatchat.runtime.news.temporal.config.NewsTemporalProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsTemporalScheduleCoordinatorTest {
    @Test
    void createsDurableScheduleWithTimezoneOverlapAndImmediateFirstRun() {
        NewsSourceRepository repository = mock(NewsSourceRepository.class);
        ScheduleClient client = mock(ScheduleClient.class);
        NewsSourceEntity source = source();
        when(repository.findAll()).thenReturn(List.of(source));
        when(client.listSchedules()).thenReturn(Stream.empty());
        var coordinator = coordinator(repository, client);

        coordinator.reconcile();

        ArgumentCaptor<Schedule> schedule = ArgumentCaptor.forClass(Schedule.class);
        verify(client).createSchedule(eq("chatchat-news-source-7"), schedule.capture(), any());
        assertThat(schedule.getValue().getSpec().getCronExpressions()).containsExactly("*/5 * * * *");
        assertThat(schedule.getValue().getSpec().getTimeZoneName()).isEqualTo("Asia/Shanghai");
        assertThat(schedule.getValue().getState().isPaused()).isFalse();
        assertThat(schedule.getValue().getAction().getClass().getSimpleName())
            .isEqualTo("ScheduleActionStartWorkflow");
    }

    @Test
    void convertsSpringCronAndRejectsSubMinuteSchedules() {
        assertThat(NewsTemporalScheduleCoordinator.temporalCron("0 10 18 * * MON-FRI"))
            .isEqualTo("10 18 * * MON-FRI");
        assertThatThrownBy(() -> NewsTemporalScheduleCoordinator.temporalCron("5 * * * * *"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("seconds must be 0");
    }

    private NewsTemporalScheduleCoordinator coordinator(NewsSourceRepository repository,
                                                         ScheduleClient client) {
        return new NewsTemporalScheduleCoordinator(repository,
            new NewsCollectionSchedulePolicy(new ObjectMapper()), client, new NewsTemporalProperties());
    }

    private NewsSourceEntity source() {
        NewsSourceEntity source = new NewsSourceEntity();
        source.setId(7L);
        source.setSourceCode("source-7");
        source.setEnabled(true);
        source.setScheduleCron("0 */5 * * * *");
        source.setConfigurationJson("{\"zoneId\":\"Asia/Shanghai\"}");
        return source;
    }
}
