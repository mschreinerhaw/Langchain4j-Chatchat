package com.chatchat.chat.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "chatchat.agent.task.event-store", name = "type", havingValue = "rocksdb", matchIfMissing = true)
public class RocksDbAgentEventStore implements AgentEventStore {

    private final AgentTaskProperties properties;
    private final ObjectMapper objectMapper;
    private Options options;
    private RocksDB db;

    /**
     * Creates a new RocksDbAgentEventStore instance.
     *
     * @param properties the properties value
     * @param objectMapper the object mapper value
     */
    public RocksDbAgentEventStore(AgentTaskProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Opens the open.
     */
    @PostConstruct
    public void open() {
        try {
            RocksDB.loadLibrary();
            Files.createDirectories(Path.of(properties.getEventStore().getPath()).toAbsolutePath());
            this.options = new Options().setCreateIfMissing(properties.getEventStore().isCreateIfMissing());
            this.db = RocksDB.open(options, properties.getEventStore().getPath());
            log.info("RocksDB agent event store opened at {}", properties.getEventStore().getPath());
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to open RocksDB agent event store", ex);
        }
    }

    /**
     * Saves the save.
     *
     * @param event the event value
     * @return the saved save
     */
    @Override
    public String save(AgentEvent event) {
        ensureOpen();
        if (event.getCreateTime() <= 0) {
            event.setCreateTime(System.currentTimeMillis());
        }
        String key = AgentEventKeyBuilder.build(event);
        try {
            db.put(bytes(key), objectMapper.writeValueAsBytes(event));
            return key;
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to write agent event", ex);
        }
    }

    /**
     * Lists the by task.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @param taskId the task id value
     * @param limit the limit value
     * @return the by task list
     */
    @Override
    public List<AgentEvent> listByTask(String tenantId, String sessionId, String taskId, int limit) {
        ensureOpen();
        List<AgentEvent> events = taskId == null || taskId.isBlank()
            ? readByPrefix(AgentEventKeyBuilder.sessionPrefix(tenantId, sessionId), null)
            : readByTaskWithLegacyFallback(tenantId, sessionId, taskId);
        return sort(events, limit);
    }

    /**
     * Reads the by task with legacy fallback.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @param taskId the task id value
     * @return the operation result
     */
    private List<AgentEvent> readByTaskWithLegacyFallback(String tenantId, String sessionId, String taskId) {
        List<AgentEvent> events = readByPrefix(AgentEventKeyBuilder.taskPrefix(tenantId, sessionId, taskId), taskId);
        if (!events.isEmpty()) {
            return events;
        }
        return readByPrefix(AgentEventKeyBuilder.sessionPrefix(tenantId, sessionId), taskId);
    }

    /**
     * Reads the by prefix.
     *
     * @param prefix the prefix value
     * @param taskId the task id value
     * @return the operation result
     */
    private List<AgentEvent> readByPrefix(String prefix, String taskId) {
        byte[] prefixBytes = bytes(prefix);
        List<AgentEvent> events = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(prefixBytes); iterator.isValid() && startsWith(iterator.key(), prefixBytes); iterator.next()) {
                AgentEvent event = objectMapper.readValue(iterator.value(), AgentEvent.class);
                if (taskId == null || taskId.equals(event.getTaskId())) {
                    events.add(event);
                }
            }
            return events;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read agent events", ex);
        }
    }

    /**
     * Performs the sort operation.
     *
     * @param events the events value
     * @param limit the limit value
     * @return the operation result
     */
    private List<AgentEvent> sort(List<AgentEvent> events, int limit) {
        return events.stream()
            .sorted(Comparator
                .comparing((AgentEvent event) -> event.getSequence() == null ? Long.MAX_VALUE : event.getSequence())
                .thenComparingLong(AgentEvent::getCreateTime))
            .limit(Math.max(1, limit))
            .toList();
    }

    /**
     * Closes the close.
     */
    @PreDestroy
    public void close() {
        if (db != null) {
            db.close();
        }
        if (options != null) {
            options.close();
        }
    }

    /**
     * Ensures the open.
     */
    private void ensureOpen() {
        if (db == null) {
            throw new IllegalStateException("RocksDB agent event store is not open");
        }
    }

    /**
     * Returns whether starts with.
     *
     * @param value the value value
     * @param prefix the prefix value
     * @return whether the condition is satisfied
     */
    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Performs the bytes operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
