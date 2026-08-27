package com.chatchat.chat.task.event;

import com.chatchat.chat.task.event.RocksDbAgentEventStore;

import com.chatchat.chat.task.event.AgentEvent;

import com.chatchat.chat.task.core.AgentTaskProperties;

import com.chatchat.chat.conversation.store.ChatDetailStoreProperties;
import com.chatchat.chat.conversation.model.ChatMessageDetail;
import com.chatchat.chat.conversation.store.ChatMessageTextStore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class RocksDbAgentEventStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void unreadableEventDoesNotPreventReadingRemainingTaskEvents() {
        AgentTaskProperties taskProperties = taskProperties("unreadable-events");
        ChatDetailStoreProperties detailProperties = detailProperties(false, 32);
        ObjectMapper constrainedMapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(32).build())
            .build());
        RocksDbAgentEventStore store = new RocksDbAgentEventStore(
            taskProperties, constrainedMapper, detailProperties, new TestTextStore()
        );
        store.open();
        try {
            store.save(event("event-1", "{}", 1L));
            store.save(event("event-too-large", "x".repeat(64), 2L));
            store.save(event("event-3", "{}", 3L));

            List<AgentEvent> events = store.listByTask("tenant-1", "session-1", "task-1", 10);

            assertThat(events)
                .extracting(AgentEvent::getEventId)
                .containsExactly("event-1", "event-3");
            assertThat(store.nextSequence("tenant-1", "session-1", "task-1")).isEqualTo(4L);
        } finally {
            store.close();
        }
    }

    @Test
    void storesLargeEventPayloadExternallyAndHydratesItOnRead() {
        ObjectMapper sharedMapper = constrainedMapper(64);
        AgentTaskProperties taskProperties = taskProperties("events");
        ChatDetailStoreProperties detailProperties = detailProperties(true, 512);
        TestTextStore textStore = new TestTextStore();
        RocksDbAgentEventStore store = new RocksDbAgentEventStore(
            taskProperties, sharedMapper, detailProperties, textStore
        );
        store.open();
        try {
            AgentEvent event = event("event-1", "p".repeat(256));

            store.save(event);

            assertThat(textStore.values).hasSize(1);
            assertThat(store.listByTask("tenant-1", "session-1", "task-1", 10))
                .singleElement()
                .extracting(AgentEvent::getPayload)
                .isEqualTo(event.getPayload());
        } finally {
            store.close();
        }
    }

    @Test
    void lazilyMigratesLegacyEventPayloadToExternalStore() {
        AgentTaskProperties taskProperties = taskProperties("legacy-events");
        ChatDetailStoreProperties legacyProperties = detailProperties(false, 512);
        TestTextStore textStore = new TestTextStore();
        RocksDbAgentEventStore legacyStore = new RocksDbAgentEventStore(
            taskProperties, constrainedMapper(512), legacyProperties, textStore
        );
        legacyStore.open();
        legacyStore.save(event("event-legacy", "l".repeat(256)));
        legacyStore.close();

        ChatDetailStoreProperties migrationProperties = detailProperties(true, 512);
        RocksDbAgentEventStore migrationStore = new RocksDbAgentEventStore(
            taskProperties, constrainedMapper(512), migrationProperties, textStore
        );
        migrationStore.open();
        assertThat(migrationStore.listByTask("tenant-1", "session-1", "task-1", 10))
            .singleElement()
            .extracting(AgentEvent::getPayload)
            .isEqualTo("l".repeat(256));
        migrationStore.close();

        ChatDetailStoreProperties strictProperties = detailProperties(true, 64);
        RocksDbAgentEventStore referenceStore = new RocksDbAgentEventStore(
            taskProperties, constrainedMapper(64), strictProperties, textStore
        );
        referenceStore.open();
        try {
            assertThat(referenceStore.listByTask("tenant-1", "session-1", "task-1", 10))
                .singleElement()
                .extracting(AgentEvent::getPayload)
                .isEqualTo("l".repeat(256));
        } finally {
            referenceStore.close();
        }
    }

    private AgentTaskProperties taskProperties(String directory) {
        AgentTaskProperties properties = new AgentTaskProperties();
        properties.getEventStore().setPath(tempDir.resolve(directory).toString());
        return properties;
    }

    private ChatDetailStoreProperties detailProperties(boolean enabled, int maxStringLength) {
        ChatDetailStoreProperties properties = new ChatDetailStoreProperties();
        properties.setMaxStringLength(maxStringLength);
        properties.getExternalText().setEnabled(enabled);
        return properties;
    }

    private ObjectMapper constrainedMapper(int maxStringLength) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder().maxStringLength(maxStringLength).build()
        );
        return mapper;
    }

    private AgentEvent event(String eventId, String payload) {
        return event(eventId, payload, 1L);
    }

    private AgentEvent event(String eventId, String payload, long sequence) {
        return AgentEvent.builder()
            .eventId(eventId)
            .tenantId("tenant-1")
            .sessionId("session-1")
            .taskId("task-1")
            .userId("user-1")
            .agentId("agent-1")
            .sequence(sequence)
            .type("TOOL_RESULT")
            .status("SUCCESS")
            .payload(payload)
            .createTime(1785280000000L)
            .build();
    }

    private static class TestTextStore implements ChatMessageTextStore {

        private final Map<String, String> values = new ConcurrentHashMap<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void put(String documentId, ChatMessageDetail detail) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ChatMessageDetail> get(String documentId) {
            return Optional.empty();
        }

        @Override
        public void putText(String documentId,
                            String kind,
                            String tenantId,
                            String sessionId,
                            String entityId,
                            String text) {
            values.put(documentId, text);
        }

        @Override
        public Optional<String> getText(String documentId) {
            return Optional.ofNullable(values.get(documentId));
        }

        @Override
        public void delete(String documentId) {
            values.remove(documentId);
        }
    }
}
