package com.chatchat.chat.conversation;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RocksDbChatMessageDetailStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void readsTraceStringsUsingDetailStoreLimitWithoutChangingSharedMapper() {
        ObjectMapper sharedMapper = new ObjectMapper().findAndRegisterModules();
        sharedMapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder().maxStringLength(64).build()
        );

        ChatDetailStoreProperties properties = new ChatDetailStoreProperties();
        properties.setPath(tempDir.resolve("chat-details").toString());
        properties.setMaxStringLength(512);

        RocksDbChatMessageDetailStore store = new RocksDbChatMessageDetailStore(
            properties, sharedMapper, new TestTextStore(false)
        );
        store.open();
        try {
            String traceOutput = "x".repeat(256);
            ChatMessageDetail detail = ChatMessageDetail.builder()
                .messageId("message-1")
                .sessionId("session-1")
                .tenantId("tenant-1")
                .userId("user-1")
                .role("assistant")
                .content("answer")
                .createdAt(Instant.parse("2026-07-29T00:00:00Z"))
                .traces(List.of(Map.of("output", traceOutput)))
                .build();

            String key = store.put(detail);

            assertThat(store.get(key)).isPresent()
                .get()
                .extracting(ChatMessageDetail::getTraces)
                .isEqualTo(detail.getTraces());
            assertThat(sharedMapper.getFactory().streamReadConstraints().getMaxStringLength())
                .isEqualTo(64);
        } finally {
            store.close();
        }
    }

    @Test
    void storesOnlyReferenceInRocksDbWhenExternalTextStoreIsEnabled() {
        ObjectMapper sharedMapper = new ObjectMapper().findAndRegisterModules();
        sharedMapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder().maxStringLength(64).build()
        );
        ChatDetailStoreProperties properties = new ChatDetailStoreProperties();
        properties.setPath(tempDir.resolve("external-chat-details").toString());
        properties.getExternalText().setEnabled(true);
        TestTextStore textStore = new TestTextStore(true);
        RocksDbChatMessageDetailStore store = new RocksDbChatMessageDetailStore(
            properties, sharedMapper, textStore
        );
        store.open();
        try {
            ChatMessageDetail detail = ChatMessageDetail.builder()
                .messageId("message-2")
                .sessionId("session-2")
                .tenantId("tenant-2")
                .userId("user-2")
                .role("assistant")
                .content("y".repeat(256))
                .createdAt(Instant.parse("2026-07-29T00:00:01Z"))
                .build();

            String key = store.put(detail);
            ChatMessageDetail secondDetail = ChatMessageDetail.builder()
                .messageId("message-3")
                .sessionId("session-2")
                .tenantId("tenant-2")
                .userId("user-2")
                .role("user")
                .content("second")
                .createdAt(Instant.parse("2026-07-29T00:00:02Z"))
                .build();
            String secondKey = store.put(secondDetail);

            assertThat(textStore.values).hasSize(2);
            assertThat(store.get(key)).contains(detail);
            assertThat(store.getAll(List.of(key, secondKey)))
                .containsEntry(key, detail)
                .containsEntry(secondKey, secondDetail);
            assertThat(textStore.batchGetCount).hasValue(1);
            assertThat(textStore.putCount).hasValue(2);
            store.delete(key);
            store.delete(secondKey);
            assertThat(textStore.values).isEmpty();
        } finally {
            store.close();
        }
    }

    @Test
    void migratesLegacyDetailOnlyOnceAcrossStoreRestart() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ChatDetailStoreProperties properties = new ChatDetailStoreProperties();
        properties.setPath(tempDir.resolve("legacy-chat-details").toString());
        TestTextStore disabledTextStore = new TestTextStore(false);
        RocksDbChatMessageDetailStore legacyStore = new RocksDbChatMessageDetailStore(
            properties, mapper, disabledTextStore
        );
        legacyStore.open();
        ChatMessageDetail detail = ChatMessageDetail.builder()
            .messageId("legacy-message")
            .sessionId("legacy-session")
            .tenantId("tenant-1")
            .userId("user-1")
            .role("assistant")
            .content("legacy content")
            .createdAt(Instant.parse("2026-07-29T00:00:02Z"))
            .build();
        String key = legacyStore.put(detail);
        legacyStore.close();

        properties.getExternalText().setEnabled(true);
        TestTextStore textStore = new TestTextStore(true);
        RocksDbChatMessageDetailStore migrationStore = new RocksDbChatMessageDetailStore(
            properties, mapper, textStore
        );
        migrationStore.open();
        assertThat(migrationStore.get(key)).contains(detail);
        assertThat(textStore.putCount).hasValue(1);
        migrationStore.close();

        RocksDbChatMessageDetailStore reopenedStore = new RocksDbChatMessageDetailStore(
            properties, mapper, textStore
        );
        reopenedStore.open();
        try {
            assertThat(reopenedStore.get(key)).contains(detail);
            assertThat(textStore.putCount).hasValue(1);
        } finally {
            reopenedStore.close();
        }
    }

    private static class TestTextStore implements ChatMessageTextStore {

        private final boolean enabled;
        private final Map<String, ChatMessageDetail> values = new ConcurrentHashMap<>();
        private final AtomicInteger putCount = new AtomicInteger();
        private final AtomicInteger batchGetCount = new AtomicInteger();

        private TestTextStore(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void put(String documentId, ChatMessageDetail detail) {
            putCount.incrementAndGet();
            values.put(documentId, detail);
        }

        @Override
        public Optional<ChatMessageDetail> get(String documentId) {
            return Optional.ofNullable(values.get(documentId));
        }

        @Override
        public Map<String, ChatMessageDetail> getAll(List<String> documentIds) {
            batchGetCount.incrementAndGet();
            Map<String, ChatMessageDetail> details = new ConcurrentHashMap<>();
            documentIds.forEach(documentId -> {
                ChatMessageDetail detail = values.get(documentId);
                if (detail != null) {
                    details.put(documentId, detail);
                }
            });
            return details;
        }

        @Override
        public void putText(String documentId,
                            String kind,
                            String tenantId,
                            String sessionId,
                            String entityId,
                            String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> getText(String documentId) {
            return Optional.empty();
        }

        @Override
        public void delete(String documentId) {
            values.remove(documentId);
        }
    }
}
