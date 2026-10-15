package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.CodingExecutionProofVerifier;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.evidence.EvidenceLineage;
import com.chatchat.common.runtime.evidence.EvidenceRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable provider response receipt. A lost response is never invented or replayed as a new model call. */
@Component
@ConditionalOnProperty(prefix = "chatchat.coding.cline", name = "enabled", havingValue = "true")
public final class ClineModelReceiptStore implements CodingExecutionProofVerifier {
    private static final String TYPE = "CODING_MODEL_RESPONSE_RECEIPT";
    private final JdbcRuntimeEvidenceStore evidence;

    public ClineModelReceiptStore(JdbcRuntimeEvidenceStore evidence) { this.evidence = evidence; }

    public Optional<Map<String, Object>> response(KernelDataScope scope, String taskId, String operationId) {
        return evidence.find(scope, id(scope, taskId, operationId)).map(record -> {
            if (!TYPE.equals(record.evidenceType()) || !taskId.equals(record.payload().get("taskId"))
                    || !operationId.equals(record.payload().get("operationId")))
                throw new SecurityException("Model receipt does not belong to this task");
            Object value = record.payload().get("response");
            if (!(value instanceof Map<?, ?> raw)) throw new IllegalStateException("Model receipt is invalid");
            @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) raw;
            return result;
        });
    }

    public String proofRef(KernelDataScope scope, String taskId, String operationId) {
        return "runtime-evidence:" + id(scope, taskId, operationId);
    }

    public String commit(CodingTaskContract contract, String operationId, String executionHandle,
                         Map<String, Object> response) {
        KernelDataScope scope = contract.scope();
        if (!executionHandle.equals("runtime-model:" + operationId))
            throw new IllegalArgumentException("Model execution identity is invalid");
        String id = id(scope, contract.taskId(), operationId);
        Map<String, Object> payload = Map.of("taskId", contract.taskId(), "operationId", operationId,
            "executionHandle", executionHandle, "contractHash",
            com.chatchat.common.runtime.coding.CodingCanonicalHash.sha256(contract), "response", response);
        String inputId = contract.inputSnapshotRef().startsWith("runtime-evidence:")
            ? contract.inputSnapshotRef().substring("runtime-evidence:".length()) : contract.inputSnapshotRef();
        if (evidence.find(scope, inputId).isEmpty())
            throw new SecurityException("Model task input snapshot is not a committed Runtime evidence record");
        evidence.register(new EvidenceRecord(null, id, scope, TYPE, "ClineRuntimeModelBridge",
                evidence.contentHash(payload), payload, null, System.currentTimeMillis(), Map.of()),
            new EvidenceLineage(null, id, scope, List.of(inputId), contract.taskId(),
                "ClineRuntimeModelBridge", operationId, Map.of()));
        return "runtime-evidence:" + id;
    }

    @Override public boolean confirmsStopped(KernelDataScope scope, String taskId, String operationId,
                                              String executionHandle, String proofRef) {
        String id = id(scope, taskId, operationId);
        if (!("runtime-evidence:" + id).equals(proofRef)) return false;
        return evidence.find(scope, id).filter(record -> TYPE.equals(record.evidenceType())
                && taskId.equals(record.payload().get("taskId"))
                && operationId.equals(record.payload().get("operationId"))
                && executionHandle.equals(record.payload().get("executionHandle"))
                && evidence.contentHash(record.payload()).equals(record.contentSha256())).isPresent();
    }

    private static String id(KernelDataScope scope, String taskId, String operationId) {
        String identity = scope.tenantId() + "\0" + scope.userId() + "\0" + scope.runId()
            + "\0" + taskId + "\0" + operationId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return "coding:model:" + HexFormat.of().formatHex(digest);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
