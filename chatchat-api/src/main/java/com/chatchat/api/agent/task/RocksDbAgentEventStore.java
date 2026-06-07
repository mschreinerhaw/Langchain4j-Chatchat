package com.chatchat.api.agent.task;

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

    public RocksDbAgentEventStore(AgentTaskProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

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

    @Override
    public List<AgentEvent> listByTask(String tenantId, String sessionId, String taskId, int limit) {
        ensureOpen();
        String prefix = AgentEventKeyBuilder.sessionPrefix(tenantId, sessionId);
        byte[] prefixBytes = bytes(prefix);
        List<AgentEvent> events = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(prefixBytes); iterator.isValid() && startsWith(iterator.key(), prefixBytes); iterator.next()) {
                AgentEvent event = objectMapper.readValue(iterator.value(), AgentEvent.class);
                if (taskId == null || taskId.equals(event.getTaskId())) {
                    events.add(event);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read agent events", ex);
        }
        return events.stream()
            .sorted(Comparator.comparingLong(AgentEvent::getCreateTime))
            .limit(Math.max(1, limit))
            .toList();
    }

    @PreDestroy
    public void close() {
        if (db != null) {
            db.close();
        }
        if (options != null) {
            options.close();
        }
    }

    private void ensureOpen() {
        if (db == null) {
            throw new IllegalStateException("RocksDB agent event store is not open");
        }
    }

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

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
