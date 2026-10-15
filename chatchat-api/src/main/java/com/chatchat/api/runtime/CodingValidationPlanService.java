package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.evidence.EvidenceLineage;
import com.chatchat.common.runtime.evidence.EvidenceRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable, trusted validation policy; the answer and rule configuration are not sent to Cline. */
@Service
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public final class CodingValidationPlanService {
    private static final String TYPE = "CODING_VALIDATION_PLAN";
    private final JdbcRuntimeEvidenceStore evidence;
    private final McpPythonControlPlanePort python;
    private final CodingInputSnapshotService inputs;
    private final CodingTaskAccess access;
    private final ObjectMapper mapper;

    /** D1 deterministic financial profile: one Decimal sum, optional unique source key. */
    public record Plan(String sourceAmountField, String resultAmountField,
                       String uniqueSourceField, boolean allowEmptyResult) {
        public Plan {
            if (!field(sourceAmountField) || !field(resultAmountField)
                    || uniqueSourceField != null && !field(uniqueSourceField))
                throw new IllegalArgumentException("Validation fields must be simple named columns");
        }
        private static boolean field(String value) {
            return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}");
        }
    }

    public CodingValidationPlanService(JdbcRuntimeEvidenceStore evidence, McpPythonControlPlanePort python,
            CodingInputSnapshotService inputs, CodingTaskAccess access, ObjectMapper mapper) {
        this.evidence = evidence; this.python = python; this.inputs = inputs;
        this.access = access; this.mapper = mapper;
    }

    /** Called by trusted Runtime preparation after the input snapshot was committed. */
    public String register(KernelDataScope scope, String taskId,
            CodingInputSnapshotService.Snapshot input, Plan plan) {
        access.require(scope, CodingTaskAccess.Action.SUBMIT);
        Objects.requireNonNull(input); Objects.requireNonNull(plan);
        String inputId = id(input.reference());
        EvidenceRecord snapshot = evidence.find(scope, inputId)
            .orElseThrow(() -> new SecurityException("Validation plan requires a committed input snapshot"));
        if (!"CODING_INPUT_SNAPSHOT".equals(snapshot.evidenceType())
                || !evidence.contentHash(snapshot.payload()).equals(snapshot.contentSha256())
                || !taskId.equals(snapshot.payload().get("taskId"))
                || !input.fileId().equals(snapshot.payload().get("fileId"))
                || !input.sha256().equals(snapshot.payload().get("sha256")))
            throw new SecurityException("Validation plan input is not bound to this task");
        Map<String, Object> payload = Map.of("taskId", taskId, "inputSnapshotRef", input.reference(),
            "profile", "DECIMAL_SUM_V1", "sourceAmountField", plan.sourceAmountField(),
            "resultAmountField", plan.resultAmountField(), "uniqueSourceField",
            plan.uniqueSourceField() == null ? "" : plan.uniqueSourceField(),
            "allowEmptyResult", plan.allowEmptyResult());
        String evidenceId = "coding:validation-plan:" + sha((scope.tenantId() + "\0"
            + scope.userId() + "\0" + scope.runId() + "\0" + taskId + "\0"
            + input.reference() + "\0" + evidence.contentHash(payload)).getBytes(StandardCharsets.UTF_8));
        evidence.register(new EvidenceRecord(null, evidenceId, scope, TYPE,
                "CodingValidationPlanService", evidence.contentHash(payload), payload,
                null, System.currentTimeMillis(), Map.of()),
            new EvidenceLineage(null, evidenceId, scope, List.of(inputId), taskId,
                "CodingValidationPlanService", null, Map.of()));
        return "runtime-evidence:" + evidenceId;
    }

    public CodingEvidenceCollector.IndependentValidator validator(CodingTaskContract contract) {
        access.require(contract.scope(), CodingTaskAccess.Action.READ);
        var input = inputs.requireAuthorized(contract);
        var scope = contract.scope();
        String ref = contract.validationPlanRef();
        if (!ref.startsWith("runtime-evidence:coding:validation-plan:"))
            throw new SecurityException("Independent validation plan is not committed Runtime evidence");
        EvidenceRecord record = evidence.find(scope, id(ref))
            .orElseThrow(() -> new SecurityException("Validation plan is unavailable"));
        Map<String, Object> value = record.payload();
        if (!TYPE.equals(record.evidenceType()) || !evidence.contentHash(value).equals(record.contentSha256())
                || !"DECIMAL_SUM_V1".equals(value.get("profile"))
                || !contract.taskId().equals(value.get("taskId"))
                || !contract.inputSnapshotRef().equals(value.get("inputSnapshotRef")))
            throw new SecurityException("Validation plan is not bound to this Coding task");
        Plan plan = new Plan((String) value.get("sourceAmountField"),
            (String) value.get("resultAmountField"),
            ((String) value.get("uniqueSourceField")).isBlank() ? null : (String) value.get("uniqueSourceField"),
            (Boolean) value.get("allowEmptyResult"));
        return rows -> validate(scope, input, plan, rows);
    }

    private CodingEvidenceCollector.Validation validate(KernelDataScope scope,
            CodingInputSnapshotService.Snapshot input, Plan plan, List<Map<String, Object>> result) {
        byte[] bytes = python.downloadDataFile(scope.tenantId(), scope.userId(), input.fileId());
        if (!sha(bytes).equals(input.sha256()))
            throw new SecurityException("Independent verifier input changed after snapshot capture");
        BigDecimal expected = BigDecimal.ZERO;
        HashSet<String> unique = new HashSet<>();
        long inputRows = 0;
        boolean inputValid = true, uniqueValid = true;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) continue;
            inputRows++;
            try {
                JsonNode row = mapper.readTree(line);
                JsonNode amount = row.path(plan.sourceAmountField());
                if (!amount.isNumber() && !amount.isTextual()) throw new IllegalArgumentException("Missing decimal source");
                expected = expected.add(new BigDecimal(amount.asText()));
                if (plan.uniqueSourceField() != null
                        && !unique.add(row.path(plan.uniqueSourceField()).asText("")))
                    uniqueValid = false;
            } catch (Exception invalid) { inputValid = false; }
        }
        if (inputRows != input.rowCount()) throw new SecurityException("Snapshot row count changed");
        boolean resultShape = result.size() == 1 || plan.allowEmptyResult() && result.isEmpty() && inputRows == 0;
        boolean amountMatches = false;
        if (result.size() == 1) {
            Object actual = result.get(0).get(plan.resultAmountField());
            try { amountMatches = actual != null && new BigDecimal(actual.toString()).compareTo(expected) == 0; }
            catch (NumberFormatException invalid) { amountMatches = false; }
        } else if (result.isEmpty() && plan.allowEmptyResult() && inputRows == 0) amountMatches = true;
        boolean passed = inputValid && uniqueValid && resultShape && amountMatches;
        return new CodingEvidenceCollector.Validation(passed, "runtime-decimal-sum", "v1",
            Map.of("inputDecimalValid", inputValid, "inputUnique", uniqueValid,
                "resultShapeValid", resultShape, "decimalReconciled", amountMatches,
                "inputRows", inputRows, "resultRows", result.size()));
    }

    private static String id(String ref) {
        if (ref == null || !ref.startsWith("runtime-evidence:"))
            throw new SecurityException("Evidence reference is invalid");
        return ref.substring("runtime-evidence:".length());
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
