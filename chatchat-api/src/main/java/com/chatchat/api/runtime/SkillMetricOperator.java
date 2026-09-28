package com.chatchat.api.runtime;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.evidence.*;
import com.chatchat.common.runtime.analysis.model.*;
import com.chatchat.runtime.skill.api.execution.*;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.skill.SkillAnalysisStep;
import com.chatchat.runtime.skill.port.outbound.SkillAnalysisOperator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/** Reuses the existing bounded decimal arithmetic implementation, not model-generated formulas. */
@Component
public class SkillMetricOperator implements SkillAnalysisOperator {
    private final VerifiedEvidenceComputationOperator computations;
    private final ObjectMapper mapper;
    public SkillMetricOperator(VerifiedEvidenceComputationOperator computations, ObjectMapper mapper) {
        this.computations = computations;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    public boolean supports(String operator) { return Set.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(operator); }
    public SkillStepResult execute(SkillAnalysisStep step, SkillDataResult data, SkillRoleContext identity) {
        try {
            if (!identity.tenantId().equals(data.provenance().get("tenantId")))
                return SkillStepResult.skipped(step.id(), "SOURCE_TENANT_MISMATCH");
            if (!(data.provenance().get("evidenceId") instanceof String evidenceId) || evidenceId.isBlank())
                return SkillStepResult.skipped(step.id(), "SOURCE_EVIDENCE_ID_MISSING");
            String content = mapper.writeValueAsString(Map.of("data", Map.of("complete", true, "rows", data.rows())));
            var source = new StructuredDataEvidence(evidenceId, data.requirement().contractId(), "canonical-data",
                data.rows().size(), "", content, Map.of("tenantId", identity.tenantId()));
            var kernel = new KernelDataScope(identity.tenantId(), identity.userId(), UUID.randomUUID().toString(), null, null, null, Map.of());
            var context = new AnalysisContext("Compute " + step.id(), kernel, "", List.of(), List.of(), identity.roleIds(), null,
                Map.of(VerifiedEvidenceComputationOperator.OPERATION, step.operator(),
                    VerifiedEvidenceComputationOperator.FIELD, step.field(),
                    AnalysisContext.EVIDENCE_BUNDLE_ATTRIBUTE, new EvidenceBundle(null, List.of(source), List.of(), Map.of())));
            var result = computations.execute(context, new AnalysisScope(identity.tenantId(), identity.userId(),
                identity.roleIds(), List.of(), Map.of()), null);
            if (result.evidence().isEmpty()) return new SkillStepResult(step.id(), "FAILED", Map.of(), List.of(), result.observations());
            var computed = result.evidence().get(0);
            Map<String, Object> output = mapper.convertValue(mapper.readTree(computed.content()),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return new SkillStepResult(step.id(), "COMPLETED", output, List.of(evidenceId, computed.evidenceId()), List.of());
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid computation payload", failure); }
    }
}
