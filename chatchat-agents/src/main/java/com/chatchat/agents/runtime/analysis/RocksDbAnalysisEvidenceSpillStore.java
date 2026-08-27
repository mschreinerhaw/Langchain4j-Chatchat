package com.chatchat.agents.runtime.analysis;

import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** RocksDB-backed, checksum-verified analysis spill and checkpoint store. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "chatchat.agent-runtime", name = "analysis-spill-enabled",
    havingValue = "true", matchIfMissing = true)
public class RocksDbAnalysisEvidenceSpillStore implements AnalysisEvidenceSpillStore {

    private static final String PAYLOAD_PREFIX = "payload:";
    private static final String CHECKPOINT_PREFIX = "checkpoint:";
    private static final String META_PREFIX = "meta:";

    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private Options options;
    private RocksDB db;

    @Autowired
    public RocksDbAnalysisEvidenceSpillStore(AgentRuntimeProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    RocksDbAnalysisEvidenceSpillStore(AgentRuntimeProperties properties,
                                      ObjectMapper objectMapper,
                                      Clock clock) {
        this.properties = properties == null ? new AgentRuntimeProperties() : properties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper.copy();
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @PostConstruct
    public synchronized void open() {
        if (db != null) return;
        try {
            RocksDB.loadLibrary();
            Path path = Path.of(properties.analysisSpillRocksDbPath()).toAbsolutePath().normalize();
            Path runStorePath = Path.of(properties.rocksDbPath()).toAbsolutePath().normalize();
            if (path.equals(runStorePath)) {
                throw new IllegalStateException("Analysis spill RocksDB path must differ from agent run store path");
            }
            Files.createDirectories(path);
            options = new Options().setCreateIfMissing(properties.isAnalysisSpillRocksDbCreateIfMissing());
            db = RocksDB.open(options, path.toString());
            deleteExpired();
            log.info("RocksDB analysis spill store opened at {}", path);
        } catch (IOException | RocksDBException ex) {
            close();
            throw new IllegalStateException("Failed to open RocksDB analysis spill store", ex);
        }
    }

    @Override
    public boolean isEnabled() {
        return properties.isAnalysisSpillEnabled();
    }

    @Override
    public SpillReference spill(GovernanceIsolationScope scope,
                                String evidenceId,
                                String contentSha256,
                                byte[] payload) {
        requireScope(scope);
        requireText(evidenceId, "Evidence id");
        byte[] safePayload = payload == null ? new byte[0] : payload;
        String actualHash = ModelProtocolJson.sha256Hex(new String(safePayload, StandardCharsets.UTF_8));
        if (contentSha256 == null || !contentSha256.equals(actualHash)) {
            throw new IllegalArgumentException("Analysis spill checksum does not match payload");
        }
        long now = clock.millis();
        String storageKey = partitionPrefix(scope) + "evidence:" + encode(evidenceId) + ":" + contentSha256;
        SpillReference reference = new SpillReference(SPILL_SCHEMA_VERSION, "ROCKSDB_ANALYSIS_SPILL",
            storageKey, evidenceId, contentSha256, safePayload.length, now);
        Map<String, Object> metadata = new LinkedHashMap<>(reference.toMap());
        metadata.put("tenantId", scope.tenantId());
        metadata.put("runId", scope.runId());
        try {
            ensureOpen();
            try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions().setSync(true)) {
                batch.put(bytes(PAYLOAD_PREFIX + storageKey), safePayload);
                batch.put(bytes(META_PREFIX + storageKey), objectMapper.writeValueAsBytes(metadata));
                db.write(writeOptions, batch);
            }
            return reference;
        } catch (RocksDBException | JsonProcessingException ex) {
            throw new IllegalStateException("Failed to spill analysis evidence " + evidenceId, ex);
        }
    }

    @Override
    public byte[] read(GovernanceIsolationScope scope, SpillReference reference) {
        requireScope(scope);
        if (reference == null || !reference.storageKey().startsWith(partitionPrefix(scope))) {
            throw new SecurityException("Cross-tenant or cross-run analysis spill read rejected");
        }
        try {
            ensureOpen();
            byte[] payload = db.get(bytes(PAYLOAD_PREFIX + reference.storageKey()));
            if (payload == null) {
                throw new IllegalStateException("Analysis spill payload is missing: " + reference.storageKey());
            }
            String actualHash = ModelProtocolJson.sha256Hex(new String(payload, StandardCharsets.UTF_8));
            if (!reference.contentSha256().equals(actualHash) || reference.byteLength() != payload.length) {
                throw new IllegalStateException("Analysis spill payload integrity check failed: " + reference.storageKey());
            }
            return payload;
        } catch (RocksDBException ex) {
            throw new IllegalStateException("Failed to read analysis spill " + reference.storageKey(), ex);
        }
    }

    @Override
    public Optional<String> readCheckpoint(GovernanceIsolationScope scope,
                                           String checkpointKey,
                                           String inputSha256) {
        requireScope(scope);
        requireText(checkpointKey, "Checkpoint key");
        requireText(inputSha256, "Checkpoint input checksum");
        try {
            ensureOpen();
            byte[] value = db.get(bytes(checkpointStorageKey(scope, checkpointKey, inputSha256)));
            if (value == null) return Optional.empty();
            Map<?, ?> envelope = objectMapper.readValue(value, Map.class);
            if (!CHECKPOINT_SCHEMA_VERSION.equals(String.valueOf(envelope.get("schemaVersion")))
                || !scope.tenantId().equals(String.valueOf(envelope.get("tenantId")))
                || !scope.runId().equals(String.valueOf(envelope.get("runId")))
                || !inputSha256.equals(String.valueOf(envelope.get("inputSha256")))) {
                throw new IllegalStateException("Analysis summary checkpoint integrity check failed");
            }
            return Optional.of(String.valueOf(envelope.get("summaryJson")));
        } catch (RocksDBException | IOException ex) {
            throw new IllegalStateException("Failed to read analysis summary checkpoint", ex);
        }
    }

    @Override
    public void checkpoint(GovernanceIsolationScope scope,
                           String checkpointKey,
                           String inputSha256,
                           String summaryJson) {
        requireScope(scope);
        requireText(checkpointKey, "Checkpoint key");
        requireText(inputSha256, "Checkpoint input checksum");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", CHECKPOINT_SCHEMA_VERSION);
        envelope.put("tenantId", scope.tenantId());
        envelope.put("runId", scope.runId());
        envelope.put("checkpointKey", checkpointKey);
        envelope.put("inputSha256", inputSha256);
        envelope.put("summaryJson", summaryJson == null ? "" : summaryJson);
        envelope.put("createdAtEpochMs", clock.millis());
        try {
            ensureOpen();
            try (WriteOptions writeOptions = new WriteOptions().setSync(true)) {
                db.put(writeOptions, bytes(checkpointStorageKey(scope, checkpointKey, inputSha256)),
                    objectMapper.writeValueAsBytes(envelope));
            }
        } catch (RocksDBException | JsonProcessingException ex) {
            throw new IllegalStateException("Failed to persist analysis summary checkpoint", ex);
        }
    }

    @Override
    public void deletePartition(GovernanceIsolationScope scope) {
        requireScope(scope);
        ensureOpen();
        deleteByPartitionPrefix(partitionPrefix(scope));
    }

    @Scheduled(fixedDelayString = "${chatchat.agent-runtime.cleanup-interval-ms:3600000}")
    public void scheduledCleanup() {
        if (properties.isCleanupEnabled()) deleteExpired();
    }

    void deleteExpired() {
        long ttl = properties.analysisSpillTtlMs();
        if (ttl <= 0 || db == null) return;
        long cutoff = clock.millis() - ttl;
        try (RocksIterator iterator = db.newIterator(); WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            for (iterator.seek(bytes(META_PREFIX)); iterator.isValid(); iterator.next()) {
                String key = text(iterator.key());
                if (!key.startsWith(META_PREFIX)) break;
                Map<?, ?> metadata = objectMapper.readValue(iterator.value(), Map.class);
                Object createdAtValue = metadata.containsKey("createdAtEpochMs")
                    ? metadata.get("createdAtEpochMs") : 0L;
                long createdAt = Long.parseLong(String.valueOf(createdAtValue));
                if (createdAt < cutoff) {
                    String storageKey = key.substring(META_PREFIX.length());
                    batch.delete(bytes(key));
                    batch.delete(bytes(PAYLOAD_PREFIX + storageKey));
                }
            }
            for (iterator.seek(bytes(CHECKPOINT_PREFIX)); iterator.isValid(); iterator.next()) {
                String key = text(iterator.key());
                if (!key.startsWith(CHECKPOINT_PREFIX)) break;
                Map<?, ?> envelope = objectMapper.readValue(iterator.value(), Map.class);
                Object createdAtValue = envelope.containsKey("createdAtEpochMs")
                    ? envelope.get("createdAtEpochMs") : 0L;
                if (Long.parseLong(String.valueOf(createdAtValue)) < cutoff) {
                    batch.delete(iterator.key());
                }
            }
            db.write(writeOptions, batch);
        } catch (RocksDBException | IOException ex) {
            throw new IllegalStateException("Failed to clean expired analysis spills", ex);
        }
    }

    private void deleteByPartitionPrefix(String partitionPrefix) {
        try (RocksIterator iterator = db.newIterator(); WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                String key = text(iterator.key());
                if (key.startsWith(PAYLOAD_PREFIX + partitionPrefix)
                    || key.startsWith(META_PREFIX + partitionPrefix)
                    || key.startsWith(CHECKPOINT_PREFIX + partitionPrefix)) {
                    batch.delete(iterator.key());
                }
            }
            db.write(writeOptions, batch);
        } catch (RocksDBException ex) {
            throw new IllegalStateException("Failed to delete analysis spill partition", ex);
        }
    }

    private String checkpointStorageKey(GovernanceIsolationScope scope,
                                        String checkpointKey,
                                        String inputSha256) {
        return CHECKPOINT_PREFIX + partitionPrefix(scope) + encode(checkpointKey) + ":" + inputSha256;
    }

    private String partitionPrefix(GovernanceIsolationScope scope) {
        return "tenant:" + encode(scope.tenantId()) + ":run:" + encode(scope.runId()) + ":";
    }

    private String encode(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
    }

    private void requireScope(GovernanceIsolationScope scope) {
        if (scope == null) throw new IllegalArgumentException("Governance isolation scope is required");
    }

    private void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
    }

    private void ensureOpen() {
        if (db == null) open();
    }

    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }

    @PreDestroy
    public synchronized void close() {
        if (db != null) { db.close(); db = null; }
        if (options != null) { options.close(); options = null; }
    }
}
