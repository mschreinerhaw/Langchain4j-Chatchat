package com.chatchat.api.runtime;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.evidence.EvidenceLineage;
import com.chatchat.common.runtime.evidence.EvidenceRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Exports only a selected DatasetHandle already delivered to the current Analysis Loop. */
@Service
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public final class CodingInputSnapshotService {
    private static final String TYPE = "CODING_INPUT_SNAPSHOT";
    private static final String FILE_NAME = "input.jsonl";
    private final JdbcRuntimeEvidenceStore evidence;
    private final McpPythonControlPlanePort python;
    private final CodingTaskAccess access;
    private final ObjectMapper mapper;

    public record Snapshot(String reference, String fileId, String fileName, String sha256,
                           long rowCount, long size, String sourceDatasetRef) { }

    public CodingInputSnapshotService(JdbcRuntimeEvidenceStore evidence,
            McpPythonControlPlanePort python, CodingTaskAccess access, ObjectMapper mapper) {
        this.evidence = evidence; this.python = python; this.access = access;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /** Authorized structural projection; no raw records, SQL, endpoint or source credentials. */
    public Map<String,Object> describe(KernelDataScope scope, String taskId, Snapshot input) {
        access.require(scope, CodingTaskAccess.Action.READ);
        String ref=input.reference().substring("runtime-evidence:".length());
        var record=evidence.find(scope,ref).orElseThrow(()->new SecurityException("Input description is unavailable"));
        if (!TYPE.equals(record.evidenceType()) || !taskId.equals(record.payload().get("taskId"))
                || !evidence.contentHash(record.payload()).equals(record.contentSha256())
                || !input.sha256().equals(record.payload().get("sha256")))
            throw new SecurityException("Input description is not bound to the task");
        var description = new LinkedHashMap<String,Object>();
        description.put("schemaJson",record.payload().get("schemaJson"));
        description.put("rowCount",input.rowCount());
        description.put("sha256",input.sha256());
        description.put("fileId",input.fileId());
        description.put("format","JSON_LINES");
        description.put("encoding","UTF-8");
        description.put("qualitySummary",record.payload().getOrDefault("qualitySummary",Map.of()));
        return Map.copyOf(description);
    }

    /** Caller supplies the current run's trusted dataset collection, never a model-supplied handle. */
    public Snapshot capture(KernelDataScope scope, String taskId, List<Dataset> authorizedDatasets,
            String sourceDatasetRef, String decisionRef, String workspaceRef,
            long maxRows, long maxBytes) {
        access.require(scope, CodingTaskAccess.Action.SUBMIT);
        if (scope == null || scope.tenantId() == null || scope.userId() == null || scope.runId() == null
                || taskId == null || taskId.isBlank() || sourceDatasetRef == null || sourceDatasetRef.isBlank()
                || decisionRef == null || decisionRef.isBlank() || workspaceRef == null || workspaceRef.isBlank()
                || maxRows < 0 || maxBytes < 1 || maxBytes > 64L * 1024 * 1024)
            throw new IllegalArgumentException("Bounded authorized Coding snapshot request is required");
        Dataset selected = Objects.requireNonNull(authorizedDatasets).stream()
            .filter(item -> sourceDatasetRef.equals(item.reference())).findFirst()
            .orElseThrow(() -> new SecurityException("Dataset was not delivered to this Analysis Loop"));
        if (!selected.handle().recordCountExact() || selected.recordCount() > maxRows)
            throw new IllegalArgumentException("Dataset has no bounded exact row count");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        final long[] rows = {0};
        TreeMap<String, TreeSet<String>> schema = new TreeMap<>();
        TreeMap<String, Long> present = new TreeMap<>(), nulls = new TreeMap<>();
        selected.handle().scan(1_000, page -> {
            for (Map<String, Object> row : page.rows()) {
                if (++rows[0] > maxRows) throw new IllegalArgumentException("Dataset row limit exceeded");
                try {
                    mapper.valueToTree(row).properties().forEach(field -> {
                        schema.computeIfAbsent(field.getKey(), ignored -> new TreeSet<>())
                            .add(field.getValue().getNodeType().name());
                        present.merge(field.getKey(),1L,Long::sum);
                        if(field.getValue().isNull())nulls.merge(field.getKey(),1L,Long::sum);
                    });
                    byte[] encoded = mapper.writeValueAsBytes(row);
                    if ((long) output.size() + encoded.length + 1 > maxBytes)
                        throw new IllegalArgumentException("Dataset byte limit exceeded");
                    output.write(encoded); output.write('\n');
                } catch (java.io.IOException invalid) {
                    throw new IllegalStateException("Dataset snapshot serialization failed", invalid);
                }
            }
        });
        if (rows[0] != selected.recordCount())
            throw new IllegalStateException("Dataset changed during snapshot capture");
        byte[] bytes = output.toByteArray();
        String schemaJson;
        try { schemaJson = mapper.writeValueAsString(schema); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Input schema cannot be encoded", invalid); }
        String hash = sha(bytes);
        String identity = scope.tenantId() + "\0" + scope.userId() + "\0" + scope.runId()
            + "\0" + taskId + "\0" + sourceDatasetRef + "\0" + decisionRef
            + "\0" + workspaceRef + "\0" + hash;
        String digest = sha(identity.getBytes(StandardCharsets.UTF_8));
        String fileId = "c" + digest.substring(0, 63);
        String evidenceId = "coding:input:" + digest;
        Map<String, Object> payload = new java.util.LinkedHashMap<>(Map.of("taskId", taskId, "sourceDatasetRef", sourceDatasetRef,
            "decisionRef", decisionRef, "workspaceRef", workspaceRef, "fileId", fileId,
            "fileName", FILE_NAME, "sha256", hash, "rowCount", rows[0], "size", bytes.length,
            "schemaJson", schemaJson));
        var quality = new LinkedHashMap<String,Object>();
        quality.put("coverage","FULL_SNAPSHOT_STRUCTURAL_ONLY");
        quality.put("rowCount",rows[0]);
        var fields = new LinkedHashMap<String,Object>();
        for(var field:schema.entrySet()) fields.put(field.getKey(),Map.of(
            "observedTypes",List.copyOf(field.getValue()),"missingCount",rows[0]-present.getOrDefault(field.getKey(),0L),
            "nullCount",nulls.getOrDefault(field.getKey(),0L)));
        quality.put("fields",fields);
        payload.put("qualitySummary",quality);
        if(selected.analysisContext().containsKey("inputVersions"))payload.put("inputVersions",selected.analysisContext().get("inputVersions"));
        var stored = python.uploadDataFile(scope.tenantId(), scope.userId(), fileId, FILE_NAME, hash, bytes);
        if (stored == null || !"AVAILABLE".equals(stored.status()) || !hash.equals(stored.fileHash())
                || stored.fileSize() != bytes.length || !fileId.equals(stored.id()))
            throw new IllegalStateException("MCP did not confirm immutable Coding input content");
        evidence.register(new EvidenceRecord(null, evidenceId, scope, TYPE,
                "CodingInputSnapshotService", evidence.contentHash(payload), payload,
                null, System.currentTimeMillis(), Map.of()),
            new EvidenceLineage(null, evidenceId, scope, List.of(), taskId,
                "CodingInputSnapshotService", null, Map.of()));
        return new Snapshot("runtime-evidence:" + evidenceId, fileId, FILE_NAME,
            hash, rows[0], bytes.length, sourceDatasetRef);
    }

    /** Resolves the one immutable input that a Coding task may mount. */
    public Snapshot requireAuthorized(CodingTaskContract contract) {
        access.require(contract.scope(), CodingTaskAccess.Action.READ);
        KernelDataScope scope = contract.scope();
        String ref = contract.inputSnapshotRef();
        if (!ref.startsWith("runtime-evidence:coding:input:"))
            throw new SecurityException("Coding input is not a trusted DatasetHandle snapshot");
        EvidenceRecord record = evidence.find(scope, ref.substring("runtime-evidence:".length()))
            .orElseThrow(() -> new SecurityException("Coding input snapshot is not accessible"));
        Map<String, Object> value = record.payload();
        if (!TYPE.equals(record.evidenceType()) || !evidence.contentHash(value).equals(record.contentSha256())
                || !contract.taskId().equals(value.get("taskId"))
                || !contract.authorization().decisionRef().equals(value.get("decisionRef"))
                || !contract.workspaceRef().equals(value.get("workspaceRef")))
            throw new SecurityException("Coding contract is not bound to the authorized input snapshot");
        String fileId = text(value, "fileId"), fileName = text(value, "fileName");
        String hash = text(value, "sha256"), source = text(value, "sourceDatasetRef");
        if (!contract.authorization().allowedDataRefs().equals(List.of(fileId))
                || !FILE_NAME.equals(fileName) || !hash.matches("[0-9a-f]{64}"))
            throw new SecurityException("Coding input file authorization differs from its snapshot");
        return new Snapshot(ref, fileId, fileName, hash,
            ((Number) value.get("rowCount")).longValue(), ((Number) value.get("size")).longValue(), source);
    }

    private static String text(Map<String, Object> value, String key) {
        Object result = value.get(key);
        if (!(result instanceof String string) || string.isBlank())
            throw new SecurityException("Coding snapshot field is missing: " + key);
        return string;
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
