package com.chatchat.chat.task.event;

import com.chatchat.chat.task.event.AgentEventBus;

import com.chatchat.chat.task.event.AgentEvent;

import com.chatchat.chat.task.core.AgentTaskProperties;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentEventBusMultiTenantConcurrencyTest {

    @Test
    void concurrentTenantRequestsRemainInTheirOwnQueues() throws Exception {
        int tenantCount = 4;
        int requestsPerTenant = 10;
        AgentTaskProperties properties = new AgentTaskProperties();
        properties.setQueueCapacity(tenantCount * requestsPerTenant);
        AgentEventBus eventBus = new AgentEventBus(properties, mock(ApplicationEventPublisher.class));
        ExecutorService executor = Executors.newFixedThreadPool(tenantCount * 2);
        try {
            List<Future<?>> publications = new ArrayList<>();
            IntStream.range(0, tenantCount).forEach(tenantIndex ->
                IntStream.range(0, requestsPerTenant).forEach(requestIndex ->
                    publications.add(executor.submit(() -> eventBus.publish(AgentEvent.builder()
                        .taskId("task-" + tenantIndex + "-" + requestIndex)
                        .tenantId("tenant-" + tenantIndex)
                        .userId("shared-user")
                        .sessionId("shared-session")
                        .type("QUESTION")
                        .status("PENDING")
                        .payload("{}")
                        .build())))
                )
            );
            for (Future<?> publication : publications) {
                publication.get(5, TimeUnit.SECONDS);
            }

            Map<String, List<AgentEvent>> eventsByTenant = new java.util.LinkedHashMap<>();
            for (int tenantIndex = 0; tenantIndex < tenantCount; tenantIndex++) {
                String tenantId = "tenant-" + tenantIndex;
                List<AgentEvent> events = new ArrayList<>();
                for (int requestIndex = 0; requestIndex < requestsPerTenant; requestIndex++) {
                    AgentEvent event = eventBus.poll(tenantId, 1, TimeUnit.SECONDS);
                    assertThat(event).isNotNull();
                    events.add(event);
                }
                eventsByTenant.put(tenantId, events);
                assertThat(eventBus.pendingQuestionCount(tenantId)).isZero();
            }

            eventsByTenant.forEach((tenantId, events) -> {
                assertThat(events).hasSize(requestsPerTenant)
                    .allSatisfy(event -> {
                        assertThat(event.getTenantId()).isEqualTo(tenantId);
                        assertThat(event.getUserId()).isEqualTo("shared-user");
                        assertThat(event.getSessionId()).isEqualTo("shared-session");
                    });
                assertThat(events)
                    .extracting(AgentEvent::getTaskId)
                    .doesNotHaveDuplicates();
            });
        } finally {
            executor.shutdownNow();
        }
    }
}
