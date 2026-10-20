package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.CodingExecutionProofVerifier;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.evidence.EvidenceLineage;
import com.chatchat.common.runtime.evidence.EvidenceRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Immutable provider response receipt. A lost response is never invented or replayed as a new model call. */
@Component
@ConditionalOnProperty(prefix = "chatchat.coding.cline", name = "enabled", havingValue = "true")
public final class ClineModelReceiptStore implements CodingExecutionProofVerifier {
    private static final String TYPE = "CODING_MODEL_RESPONSE_RECEIPT";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
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

    public Optional<com.chatchat.common.runtime.coding.CodingModelCallTelemetry> telemetry(
            CodingTaskContract contract,String operationId,long fencingToken) {
        if(operationId==null || !operationId.matches("model-[1-9][0-9]{0,5}"))
            throw new IllegalArgumentException("Invalid model operation identity");
        var scope=contract.scope();
        return evidence.find(scope,id(scope,contract.taskId(),operationId)).map(record->{
            if(!TYPE.equals(record.evidenceType()) || !contract.taskId().equals(record.payload().get("taskId"))
                    || !operationId.equals(record.payload().get("operationId"))
                    || !com.chatchat.common.runtime.coding.CodingCanonicalHash.sha256(contract).equals(record.payload().get("contractHash"))
                    || !validContentHash(record))
                throw new SecurityException("Model telemetry receipt binding differs from contract");
            if(!(record.payload().get("response") instanceof Map<?,?> response))
                throw new IllegalStateException("Invalid model receipt");
            Map<?,?> info=response.get("modelInfo") instanceof Map<?,?> value?value:Map.of();
            var usage=new java.util.LinkedHashMap<String,Long>();
            if(response.get("usage") instanceof Map<?,?> values)
                for(String key:List.of("inputTokens","outputTokens","cacheReadTokens","cacheWriteTokens","reasoningTokens")) {
                    Long value=number(values.get(key));if(value!=null)usage.put(key,value);
                }
            return new com.chatchat.common.runtime.coding.CodingModelCallTelemetry(operationId,
                fencingToken>0?contract.taskId()+":fence-"+fencingToken:null,Integer.parseInt(operationId.substring(6)),"SUCCEEDED",
                label(info.get("selectedModel")),label(info.get("providerModel")),label(info.get("protocol")),
                label(info.get("startedAt")),label(info.get("finishedAt")),number(info.get("durationMs")),usage,"JAVA_MODEL_RECEIPT");
        });
    }
    private static String label(Object value) {
        return value instanceof String text && text.length()<=512 && text.chars().noneMatch(Character::isISOControl)?text:null;
    }
    private static Long number(Object value) {
        return value instanceof Number number && number.longValue()>=0?number.longValue():null;
    }

    public String commit(CodingTaskContract contract, String operationId, String executionHandle,
                         Map<String, Object> response) {
        KernelDataScope scope = contract.scope();
        if (!executionHandle.equals("runtime-model:" + operationId))
            throw new IllegalArgumentException("Model execution identity is invalid");
        String id = id(scope, contract.taskId(), operationId);
        Map<String, Object> payload = Map.of("taskId", contract.taskId(), "operationId", operationId,
            "executionHandle", executionHandle, "contractHash",
            com.chatchat.common.runtime.coding.CodingCanonicalHash.sha256(contract), "response",
            JSON.convertValue(response, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {}));
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
                && validContentHash(record)).isPresent();
    }

    private boolean validContentHash(EvidenceRecord record) {
        if (evidence.contentHash(record.payload()).equals(record.contentSha256())) return true;
        // Legacy bridge responses stored tool input as ObjectNode: ORDER_MAP_ENTRIES_BY_KEYS
        // sorted Maps but not that node. Reconstruct only this declared representation and
        // still require equality with the ORIGINAL immutable SHA-256; never rewrite evidence.
        if (!(record.payload().get("response") instanceof Map<?,?> response)
                || !(response.get("toolCalls") instanceof List<?> calls)) return false;
        var restored = new java.util.LinkedHashMap<String,Object>();
        response.forEach((key,value) -> restored.put(String.valueOf(key),value));
        var tools = new java.util.ArrayList<Map<String,Object>>();
        for (Object value : calls) {
            if (!(value instanceof Map<?,?> call) || !(call.get("input") instanceof Map<?,?> input)) return false;
            var tool = new java.util.LinkedHashMap<String,Object>();
            call.forEach((key,item) -> tool.put(String.valueOf(key),item));
            tool.put("input", JSON.valueToTree(input));
            tools.add(tool);
        }
        restored.put("toolCalls",tools);
        var payload = new java.util.LinkedHashMap<>(record.payload());
        payload.put("response",restored);
        return evidence.contentHash(payload).equals(record.contentSha256());
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
