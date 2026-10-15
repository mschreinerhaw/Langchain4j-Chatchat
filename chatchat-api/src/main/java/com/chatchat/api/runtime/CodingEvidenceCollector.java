package com.chatchat.api.runtime;

import com.chatchat.agents.orchestration.analysis.dataset.DatasetHandle;
import com.chatchat.agents.orchestration.analysis.dataset.InMemoryDatasetHandle;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Archives verified Python facts in the existing Runtime Evidence Store; never creates a business claim. */
@Service
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public class CodingEvidenceCollector {
    public enum Stage { STAGED, VERIFIED, COMMITTED }
    public record InputFile(String fileId, String fileName, String sha256) { }
    public record Validation(boolean passed, String verifierId, String ruleVersion, Map<String, Object> checks,
                             boolean businessEvaluated) {
        public Validation(boolean passed, String verifierId, String ruleVersion, Map<String,Object> checks) {
            this(passed, verifierId, ruleVersion, checks, true);
        }
        public Validation(boolean passed, Map<String, Object> checks) {
            this(passed, "runtime-deterministic-validator", "v1", checks);
        }
        public Validation {
            if (verifierId == null || verifierId.isBlank() || ruleVersion == null || ruleVersion.isBlank())
                throw new IllegalArgumentException("Independent validation provenance is required");
            checks = checks == null ? Map.of() : Map.copyOf(checks);
        }
        public com.chatchat.common.runtime.coding.CodingTaskOutcome.ValidationStatus status() {
            return !passed ? com.chatchat.common.runtime.coding.CodingTaskOutcome.ValidationStatus.FAILED
                : businessEvaluated ? com.chatchat.common.runtime.coding.CodingTaskOutcome.ValidationStatus.PASSED
                : com.chatchat.common.runtime.coding.CodingTaskOutcome.ValidationStatus.INCONCLUSIVE;
        }
    }
    @FunctionalInterface public interface IndependentValidator {
        Validation validate(List<Map<String, Object>> rows);
    }
    public record Collected(String inputSnapshotRef, String codeArtifactRef, String executionReceiptRef,
                            String resultDatasetRef, String validationRecordRef, String evidenceBundleRef,
                            boolean businessValidationPassed,
                            com.chatchat.common.runtime.coding.CodingTaskOutcome.ValidationStatus validationStatus) { }

    private final JdbcRuntimeEvidenceStore evidence;
    private final RuntimeExecutionCheckpointPort checkpoints;
    private final DurableCodingTaskService tasks;
    private final McpPythonControlPlanePort python;
    private final ObjectMapper mapper;

    public CodingEvidenceCollector(JdbcRuntimeEvidenceStore evidence, RuntimeExecutionCheckpointPort checkpoints,
            DurableCodingTaskService tasks, McpPythonControlPlanePort python, ObjectMapper mapper) {
        this.evidence = evidence; this.checkpoints = checkpoints; this.tasks = tasks; this.python = python;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /** Retry-safe after a crash at either stage boundary. The executor is never restarted here. */
    public Collected collect(KernelDataScope scope, CodingTaskContract contract, String operationId,
            String codeFile, String resultFile, List<InputFile> inputs, IndependentValidator validator) {
        Objects.requireNonNull(contract); Objects.requireNonNull(validator);
        if (!sameOwner(scope, contract.scope())) throw new SecurityException("Coding task owner mismatch");
        tasks.get(scope, contract.taskId()).validateAgainst(contract);
        if (!contract.outputContract().requiredFiles().contains("results/" + resultFile)
                && !contract.outputContract().requiredFiles().contains(resultFile))
            throw new IllegalArgumentException("Result file is not in the output contract");
        var operation = python.get(scope.tenantId(), scope.userId(), scope.runId(), contract.taskId(), operationId);
        if (!operation.executorStopped() || !"SUCCEEDED".equals(operation.status()) || operation.manifestHash() == null)
            throw new IllegalStateException("A stopped, successful, archived operation is required");
        byte[] manifestBytes = artifact(scope, contract.taskId(), operationId, "manifest", "manifest.json");
        if (!sha(manifestBytes).equals(operation.manifestHash())) throw new IllegalStateException("Manifest hash differs from execution receipt");
        JsonNode manifest = parse(manifestBytes);
        if (!manifest.path("artifacts").isArray()) throw new IllegalStateException("Artifact manifest is invalid");
        Map<String, JsonNode> entries = new HashMap<>();
        for (JsonNode entry : manifest.path("artifacts")) {
            String channel = entry.path("channel").asText();
            String name = entry.path("fileName").asText();
            if (!Set.of("results", "validation", "logs").contains(channel) || name.isBlank()
                    || entries.putIfAbsent(channel + "/" + name, entry) != null)
                throw new IllegalStateException("Artifact manifest has an invalid or repeated entry");
            byte[] bytes = artifact(scope, contract.taskId(), operationId, channel, name);
            verifyArtifact(entry, bytes);
            if ("results".equals(channel) && name.endsWith(".jsonl")) rows(bytes, entry);
        }
        JsonNode resultEntry = entries.get("results/" + resultFile);
        JsonNode receiptEntry = entries.get("logs/receipt.json");
        if (receiptEntry == null || !resultFile.endsWith(".jsonl"))
            throw new IllegalStateException("Structured result or execution receipt is missing");
        if (resultEntry == null) throw new RequiredOutputMissingException(resultFile);
        byte[] resultBytes = artifact(scope, contract.taskId(), operationId, "results", resultFile);
        List<Map<String, Object>> resultRows = rows(resultBytes, resultEntry);
        if (resultBytes.length > contract.outputContract().maxResultBytes()
                || resultRows.size() > contract.outputContract().maxResultRows())
            throw new IllegalStateException("Result exceeds the authorized output contract");
        String overall = manifest.path("resultCompleteness").asText();
        String expected = resultRows.isEmpty() ? "EMPTY" : "PRESENT";
        if (!expected.equals(resultEntry.path("completeness").asText())
                || !("PRESENT".equals(overall) || "EMPTY".equals(overall)))
            throw new IllegalStateException("Result is incomplete");
        byte[] code = artifact(scope, contract.taskId(), operationId, "code", codeFile);
        if (!sha(code).equals(operation.codeHash())) throw new IllegalStateException("Executed code hash mismatch");
        byte[] receipt = artifact(scope, contract.taskId(), operationId, "logs", "receipt.json");
        JsonNode receiptJson = parse(receipt);
        var binding = tasks.verifiedOperation(scope, contract.taskId(), operationId)
            .orElseThrow(() -> new IllegalStateException("Operation has no A2-verified stop proof"));
        if (!receiptJson.path("operationId").asText().equals(operationId)
                || !receiptJson.path("stopConfirmed").asBoolean()
                || receiptJson.path("fencingToken").asLong() != operation.fencingToken()
                || binding.fencingToken() != operation.fencingToken()
                || !binding.stopProofRef().equals("mcp-python-receipt:" + sha(receipt))
                || !receiptJson.path("containerName").asText().equals(operation.containerId())
                || !receiptJson.path("imageDigest").asText().equals(operation.imageDigest()))
            throw new IllegalStateException("Execution receipt does not prove this stopped operation");
        long occurredAt = java.time.Instant.parse(receiptJson.path("startedAt").asText()).toEpochMilli();
        List<Map<String, Object>> inputFacts = new ArrayList<>();
        for (InputFile input : inputs == null ? List.<InputFile>of() : inputs) {
            if (!contract.authorization().allowedDataRefs().contains(input.fileId())
                    && !contract.authorization().allowedDataRefs().contains("mcp-python:data-file:" + input.fileId()))
                throw new SecurityException("Input file is not authorized");
            byte[] bytes = artifact(scope, contract.taskId(), operationId, "input-" + input.fileId(), input.fileName());
            if (!sha(bytes).equals(input.sha256())) throw new IllegalStateException("Input snapshot hash mismatch");
            inputFacts.add(Map.of("fileId", input.fileId(), "fileName", input.fileName(),
                "sha256", input.sha256(), "size", bytes.length));
        }
        var existingInput = evidence.find(scope, id(contract.inputSnapshotRef()));
        if (existingInput.isEmpty() && inputFacts.isEmpty())
            throw new IllegalStateException("No immutable input snapshot is available");
        if (existingInput.isPresent()) {
            var stored = existingInput.get();
            boolean trustedPrecaptured = contract.taskId().equals(stored.payload().get("taskId"));
            boolean collectorArchived = contract.inputSnapshotRef().equals(stored.payload().get("contractRef"))
                && inputFacts.equals(stored.payload().get("files"));
            if (!"CODING_INPUT_SNAPSHOT".equals(stored.evidenceType())
                    || !evidence.contentHash(stored.payload()).equals(stored.contentSha256())
                    || !(trustedPrecaptured || collectorArchived))
                throw new SecurityException("Input snapshot is not bound to this Coding task");
            if (trustedPrecaptured && !inputFacts.isEmpty() && (inputFacts.size() != 1
                    || !Objects.equals(inputFacts.get(0).get("fileId"), stored.payload().get("fileId"))
                    || !Objects.equals(inputFacts.get(0).get("fileName"), stored.payload().get("fileName"))
                    || !Objects.equals(inputFacts.get(0).get("sha256"), stored.payload().get("sha256"))))
                throw new SecurityException("Archived input differs from the authorized snapshot");
        }
        Validation validation = Objects.requireNonNull(validator.validate(List.copyOf(resultRows)), "independent validation");
        String stem = "coding:" + sha((scope.tenantId() + "\0" + scope.userId() + "\0" + scope.runId()
                + "\0" + contract.taskId() + "\0" + operationId).getBytes(StandardCharsets.UTF_8));
        String inputId = id(contract.inputSnapshotRef());
        String codeId = stem + ":code", receiptId = stem + ":receipt", resultId = stem + ":result";
        String validationId = stem + ":validation", bundleId = stem + ":bundle";
        List<EvidenceRecord> records = new ArrayList<>();
        List<EvidenceLineage> lineages = new ArrayList<>();
        if (!inputFacts.isEmpty() && (existingInput.isEmpty()
                || !contract.taskId().equals(existingInput.get().payload().get("taskId"))))
            add(records, lineages, scope, inputId, "CODING_INPUT_SNAPSHOT",
            Map.of("files", inputFacts, "contractRef", contract.inputSnapshotRef()), List.of(), contract.taskId(), occurredAt);
        add(records, lineages, scope, codeId, "CODING_CODE_ARTIFACT",
            location(operationId, "code", codeFile, sha(code), code.length), List.of(inputId), contract.taskId(), occurredAt);
        add(records, lineages, scope, receiptId, "CODING_EXECUTION_RECEIPT",
            location(operationId, "logs", "receipt.json", sha(receipt), receipt.length), List.of(codeId), contract.taskId(), occurredAt);
        Map<String, Object> result = new LinkedHashMap<>(location(operationId, "results", resultFile, sha(resultBytes), resultBytes.length));
        result.put("rowCount", resultRows.size()); result.put("schemaJson", resultEntry.path("schemaJson").asText(""));
        result.put("completeness", expected); result.put("resultSchemaRef", contract.outputContract().resultSchemaRef());
        add(records, lineages, scope, resultId, "CODING_STRUCTURED_DATASET", result,
            List.of(inputId, codeId, receiptId), contract.taskId(), occurredAt);
        add(records, lineages, scope, validationId, "CODING_COMPUTATION_VALIDATION",
            Map.of("status", validation.status().name(), "checks", validation.checks(),
                "structuralChecksPassed", validation.passed(), "businessEvaluated", validation.businessEvaluated(),
                "verifierId", validation.verifierId(), "ruleVersion", validation.ruleVersion(),
                "validationPlanRef", contract.validationPlanRef(), "resultDatasetRef", "runtime-evidence:" + resultId),
            List.of(resultId), contract.taskId(), occurredAt);
        Map<String, Object> bundleMetadata = Map.of("taskId", contract.taskId(), "operationId", operationId,
            "manifestSha256", sha(manifestBytes), "validationPassed", validation.passed(),
            "businessEvaluated", validation.businessEvaluated());
        String key = "coding-evidence:" + stem;
        String stageBase = sha(mapperBytes(Map.of("records", records, "lineages", lineages,
            "bundleId", bundleId, "metadata", bundleMetadata)));
        List<String> bundleEvidenceIds = List.of(inputId, codeId, receiptId, resultId, validationId);
        String staged = stageJson(Stage.STAGED, stageBase), verified = stageJson(Stage.VERIFIED, stageBase);
        String committed = stageJson(Stage.COMMITTED, stageBase);
        Optional<String> current = checkpoints.readExecutionCheckpoint(scope, key);
        if (current.isEmpty()) {
            if (!checkpoints.compareAndSetExecutionCheckpoint(scope, key, null, staged))
                current = checkpoints.readExecutionCheckpoint(scope, key);
            else current = Optional.of(staged);
        }
        if (current.get().equals(staged)) {
            if (!checkpoints.compareAndSetExecutionCheckpoint(scope, key, staged, verified))
                current = checkpoints.readExecutionCheckpoint(scope, key);
            else current = Optional.of(verified);
        }
        if (!current.get().equals(verified) && !current.get().equals(committed))
            throw new IllegalStateException("Coding evidence stage or content changed during recovery");
        evidence.commitCodingEvidence(scope, key, verified, committed, records, lineages,
            bundleId, bundleEvidenceIds, bundleMetadata);
        return new Collected(contract.inputSnapshotRef(), "runtime-evidence:" + codeId,
            "runtime-evidence:" + receiptId, "runtime-evidence:" + resultId,
            "runtime-evidence:" + validationId, "runtime-evidence:" + bundleId,
            validation.passed() && validation.businessEvaluated(), validation.status());
    }

    /** A verified archive without the contracted result is not a binding/authentication failure. */
    public static final class RequiredOutputMissingException extends IllegalStateException {
        private final String requiredFile;
        public RequiredOutputMissingException(String requiredFile) {
            super("Successful Python execution did not produce the required structured result");
            this.requiredFile=requiredFile;
        }
        public String requiredFile(){return requiredFile;}
    }

    /** Reads the complete archived dataset only through the task's authorized operation scope. */
    public DatasetHandle openResult(KernelDataScope scope, String taskId, String resultRef) {
        EvidenceRecord record = resolve(scope, taskId, resultRef);
        if (!"CODING_STRUCTURED_DATASET".equals(record.evidenceType())) throw new IllegalArgumentException("Not a structured dataset");
        Map<String, Object> p = record.payload();
        byte[] bytes = artifact(scope, taskId, (String) p.get("operationId"), (String) p.get("channel"), (String) p.get("fileName"));
        if (!sha(bytes).equals(p.get("artifactSha256")) || bytes.length != ((Number)p.get("size")).longValue())
            throw new IllegalStateException("Archived dataset changed");
        List<Map<String, Object>> result = rows(bytes, null);
        if (result.size() != ((Number)p.get("rowCount")).longValue()) throw new IllegalStateException("Archived dataset row count changed");
        return new InMemoryDatasetHandle(result);
    }

    /** Existing analysis workspace registration shape; callers decide whether to admit it into an Analysis Loop. */
    public AnalysisEvidenceCoordinator.Dataset registeredResult(KernelDataScope scope, String taskId, String resultRef) {
        EvidenceRecord record = resolve(scope, taskId, resultRef);
        if (!"CODING_STRUCTURED_DATASET".equals(record.evidenceType())) throw new IllegalArgumentException("Not a structured dataset");
        EvidenceLineage lineage = evidence.lineage(scope, record.evidenceId()).orElseThrow();
        return new AnalysisEvidenceCoordinator.Dataset(resultRef,
            Map.of("evidenceRef", resultRef, "taskId", taskId, "rowCount", record.payload().get("rowCount"),
                "schemaJson", record.payload().get("schemaJson"), "parentEvidenceIds", lineage.parentEvidenceIds()),
            openResult(scope, taskId, resultRef));
    }

    /** Resolves only records produced by the caller's authorized task, including bundle membership. */
    public EvidenceRecord resolve(KernelDataScope scope, String taskId, String reference) {
        var task = tasks.get(scope, taskId);
        String evidenceId = id(reference);
        EvidenceRecord record = evidence.find(scope, evidenceId)
            .orElseThrow(() -> new SecurityException("Evidence is not accessible"));
        EvidenceLineage lineage = evidence.lineage(scope, evidenceId)
            .orElseThrow(() -> new IllegalStateException("Evidence lineage is missing"));
        if (!taskId.equals(lineage.producingTaskId()) && !reference.equals(task.inputSnapshotRef()))
            throw new SecurityException("Evidence belongs to another task");
        return record;
    }

    public EvidenceSnapshot resolveBundle(KernelDataScope scope, String taskId, String bundleRef) {
        tasks.get(scope, taskId);
        EvidenceSnapshot bundle = evidence.snapshot(scope, id(bundleRef))
            .orElseThrow(() -> new SecurityException("Evidence bundle is not accessible"));
        if (!taskId.equals(bundle.metadata().get("taskId")) || bundle.evidenceIds().size() != 5)
            throw new SecurityException("Evidence bundle does not belong to this task");
        for (String member : bundle.evidenceIds()) resolve(scope, taskId, "runtime-evidence:" + member);
        return bundle;
    }

    private void add(List<EvidenceRecord> records, List<EvidenceLineage> lineages, KernelDataScope scope,
            String id, String type, Map<String, Object> payload, List<String> parents, String taskId, long occurredAt) {
        records.add(new EvidenceRecord(null, id, scope, type, "CodingEvidenceCollector", evidence.contentHash(payload),
            payload, null, occurredAt, Map.of()));
        lineages.add(new EvidenceLineage(null, id, scope, parents, taskId, "CodingEvidenceCollector", null, Map.of()));
    }
    private byte[] artifact(KernelDataScope scope, String taskId, String operationId, String channel, String name) {
        return python.artifact(scope.tenantId(), scope.userId(), scope.runId(), taskId, operationId, channel, name);
    }
    private static Map<String, Object> location(String op, String channel, String name, String hash, long size) {
        return Map.of("operationId", op, "channel", channel, "fileName", name, "artifactSha256", hash, "size", size);
    }
    private static void verifyArtifact(JsonNode entry, byte[] bytes) {
        if (!sha(bytes).equals(entry.path("sha256").asText()) || bytes.length != entry.path("size").asLong(-1))
            throw new IllegalStateException("Archived artifact hash or size mismatch");
    }
    private List<Map<String, Object>> rows(byte[] bytes, JsonNode manifestEntry) {
        List<Map<String, Object>> result = new ArrayList<>();
        String schema = null;
        try (BufferedReader reader = new BufferedReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode row = mapper.readTree(line);
                if (!row.isObject()) throw new IllegalStateException("JSONL row must be an object");
                TreeMap<String, String> types = new TreeMap<>();
                row.properties().forEach(e -> types.put(e.getKey(), e.getValue().getNodeType().name()));
                String nextSchema = mapper.writeValueAsString(types);
                if (schema != null && !schema.equals(nextSchema)) throw new IllegalStateException("JSONL schema changed");
                schema = nextSchema;
                result.add(mapper.convertValue(row, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            }
        } catch (java.io.IOException error) { throw new IllegalStateException("JSONL cannot be read", error); }
        if (manifestEntry != null && (manifestEntry.path("rows").asLong(-1) != result.size()
                || !Objects.equals(schema == null ? "" : schema, manifestEntry.path("schemaJson").asText(""))))
            throw new IllegalStateException("JSONL schema or row count differs from manifest");
        return List.copyOf(result);
    }
    private JsonNode parse(byte[] bytes) {
        try { return mapper.readTree(bytes); }
        catch (Exception error) { throw new IllegalStateException("Archived JSON is invalid", error); }
    }
    private byte[] mapperBytes(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (Exception error) { throw new IllegalStateException("Evidence stage cannot be encoded", error); }
    }
    private static String stageJson(Stage stage, String hash) { return "{\"stage\":\"" + stage + "\",\"batchSha256\":\"" + hash + "\"}"; }
    private static boolean sameOwner(KernelDataScope a, KernelDataScope b) {
        return a != null && b != null && Objects.equals(a.tenantId(), b.tenantId())
            && Objects.equals(a.userId(), b.userId()) && Objects.equals(a.runId(), b.runId());
    }
    private static String id(String ref) {
        if (ref == null || ref.isBlank()) throw new IllegalArgumentException("Evidence reference is required");
        return ref.startsWith("runtime-evidence:") ? ref.substring("runtime-evidence:".length()) : ref;
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
