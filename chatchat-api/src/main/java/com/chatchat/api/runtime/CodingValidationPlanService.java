package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.evidence.EvidenceLineage;
import com.chatchat.common.runtime.evidence.EvidenceRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Persisted validation contracts. Domain algorithms belong to explicitly registered trusted extensions. */
@Service
@ConditionalOnProperty(prefix = "chatchat.coding", name = "enabled", havingValue = "true")
public final class CodingValidationPlanService {
    private static final String TYPE = "CODING_VALIDATION_PLAN";
    private final JdbcRuntimeEvidenceStore evidence;
    private final CodingInputSnapshotService inputs;
    private final CodingTaskAccess access;
    private final ObjectMapper mapper;
    private final Map<String, CodingResultVerifier> verifiers;

    public record Plan(String verifierId, String ruleVersion, Map<String, Object> rules) {
        public Plan {
            if (verifierId == null || !verifierId.matches("[A-Za-z0-9_.-]{1,128}")
                    || ruleVersion == null || !ruleVersion.matches("[A-Za-z0-9_.-]{1,64}"))
                throw new IllegalArgumentException("Versioned verifier identity is required");
            rules = Map.copyOf(Objects.requireNonNull(rules));
        }
    }

    public CodingValidationPlanService(JdbcRuntimeEvidenceStore evidence, CodingInputSnapshotService inputs,
            CodingTaskAccess access, ObjectMapper mapper, List<CodingResultVerifier> extensions) {
        this.evidence = evidence; this.inputs = inputs; this.access = access; this.mapper = mapper;
        Map<String, CodingResultVerifier> registered = new HashMap<>();
        for (var verifier : extensions) {
            if (registered.putIfAbsent(verifier.id() + ":" + verifier.version(), verifier) != null)
                throw new IllegalStateException("Duplicate Coding verifier identity");
        }
        this.verifiers = Map.copyOf(registered);
    }

    public String register(KernelDataScope scope, String taskId,
            CodingInputSnapshotService.Snapshot input, Plan proposed) {
        access.require(scope, CodingTaskAccess.Action.SUBMIT);
        Objects.requireNonNull(input); Objects.requireNonNull(proposed);
        var plan = detached(proposed);
        verifier(plan).validateRules(plan.rules());
        String inputId = id(input.reference());
        EvidenceRecord snapshot = evidence.find(scope, inputId)
            .orElseThrow(() -> new SecurityException("Validation plan requires a committed input snapshot"));
        if (!"CODING_INPUT_SNAPSHOT".equals(snapshot.evidenceType())
                || !evidence.contentHash(snapshot.payload()).equals(snapshot.contentSha256())
                || !taskId.equals(snapshot.payload().get("taskId"))
                || !input.fileId().equals(snapshot.payload().get("fileId"))
                || !input.sha256().equals(snapshot.payload().get("sha256")))
            throw new SecurityException("Validation input is not bound to this task");
        Map<String, Object> payload = Map.of("taskId", taskId, "inputSnapshotRef", input.reference(),
            "verifierId", plan.verifierId(), "ruleVersion", plan.ruleVersion(), "rules", plan.rules());
        String evidenceId = "coding:validation-plan:" + sha((scope.tenantId() + "\0" + scope.userId()
            + "\0" + scope.runId() + "\0" + taskId + "\0" + input.reference() + "\0"
            + evidence.contentHash(payload)).getBytes(StandardCharsets.UTF_8));
        evidence.register(new EvidenceRecord(null, evidenceId, scope, TYPE, "CodingValidationPlanService",
                evidence.contentHash(payload), payload, null, System.currentTimeMillis(), Map.of()),
            new EvidenceLineage(null, evidenceId, scope, List.of(inputId), taskId,
                "CodingValidationPlanService", null, Map.of()));
        return "runtime-evidence:" + evidenceId;
    }

    public CodingEvidenceCollector.IndependentValidator validator(CodingTaskContract contract) {
        access.require(contract.scope(), CodingTaskAccess.Action.READ);
        var input = inputs.requireAuthorized(contract);
        var scope = contract.scope();
        if (!contract.validationPlanRef().startsWith("runtime-evidence:coding:validation-plan:"))
            throw new SecurityException("Validation plan is not committed Runtime evidence");
        EvidenceRecord record = evidence.find(scope, id(contract.validationPlanRef()))
            .orElseThrow(() -> new SecurityException("Validation plan is unavailable"));
        var value = record.payload();
        if (!TYPE.equals(record.evidenceType()) || !evidence.contentHash(value).equals(record.contentSha256())
                || !contract.taskId().equals(value.get("taskId"))
                || !contract.inputSnapshotRef().equals(value.get("inputSnapshotRef")))
            throw new SecurityException("Validation plan is not bound to this Coding task");
        // Legacy plans need their original trusted verifier; never reinterpret them as structural checks.
        if (!(value.get("verifierId") instanceof String verifierId)
                || !(value.get("ruleVersion") instanceof String version))
            throw new IllegalStateException("Legacy validation plan needs its original trusted verifier");
        @SuppressWarnings("unchecked") var rules = (Map<String, Object>) value.get("rules");
        var plan = detached(new Plan(verifierId, version, rules));
        var verifier = verifier(plan);
        verifier.validateRules(plan.rules());
        return rows -> verifier.verify(scope, input, plan.rules(), rows);
    }

    private CodingResultVerifier verifier(Plan plan) {
        var verifier = verifiers.get(plan.verifierId() + ":" + plan.ruleVersion());
        if (verifier == null) throw new SecurityException("Coding verifier is not registered by Runtime");
        return verifier;
    }
    private Plan detached(Plan plan) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(plan);
            if (bytes.length > 65536) throw new IllegalArgumentException("Validation plan exceeds limit");
            return mapper.readValue(bytes, Plan.class);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid validation plan", invalid); }
    }
    private static String id(String ref) {
        if (ref == null || !ref.startsWith("runtime-evidence:")) throw new SecurityException("Invalid evidence reference");
        return ref.substring("runtime-evidence:".length());
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
