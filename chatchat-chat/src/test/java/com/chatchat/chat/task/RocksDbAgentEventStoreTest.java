package com.chatchat.chat.task;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RocksDbAgentEventStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void unreadableEventDoesNotPreventReadingRemainingTaskEvents() {
        AgentTaskProperties properties = new AgentTaskProperties();
        properties.getEventStore().setPath(tempDir.resolve("events").toString());
        ObjectMapper constrainedMapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(32).build())
            .build());
        RocksDbAgentEventStore store = new RocksDbAgentEventStore(properties, constrainedMapper);
        store.open();
        try {
            store.save(event("event-1", 1L, "{}"));
            store.save(event("event-too-large", 2L, "x".repeat(64)));
            store.save(event("event-3", 3L, "{}"));

            List<AgentEvent> events = store.listByTask("tenant", "session", "task", 10);

            assertThat(events)
                .extracting(AgentEvent::getEventId)
                .containsExactly("event-1", "event-3");
            assertThat(store.nextSequence("tenant", "session", "task")).isEqualTo(4L);
        } finally {
            store.close();
        }
    }

    private AgentEvent event(String eventId, long sequence, String payload) {
        return AgentEvent.builder()
            .eventId(eventId)
            .tenantId("tenant")
            .sessionId("session")
            .taskId("task")
            .type("RUNTIME_OBSERVATION")
            .status("RUNNING")
            .sequence(sequence)
            .payload(payload)
            .createTime(sequence)
            .build();
    }
}
