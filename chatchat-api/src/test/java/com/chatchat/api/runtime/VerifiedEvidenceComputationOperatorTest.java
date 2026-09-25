package com.chatchat.api.runtime;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.evidence.StructuredDataEvidence;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VerifiedEvidenceComputationOperatorTest {
    private final VerifiedEvidenceComputationOperator operator =
        new VerifiedEvidenceComputationOperator(new ObjectMapper());

    @Test void computesMetricFromVerifiedCompleteRowsWithProvenance() {
        var result = operator.execute(context(true, "tenant"), scope(), null);

        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).content()).contains("42");
        assertThat(result.evidence().get(0).attributes()).containsKey("remoteProjection");
    }

    @Test void refusesIncompleteOrCrossTenantData() {
        assertThat(operator.execute(context(false, "tenant"), scope(), null).evidence()).isEmpty();
        assertThat(operator.execute(context(true, "other"), scope(), null).evidence()).isEmpty();
    }

    private AnalysisContext context(boolean complete, String tenant) {
        var source = new StructuredDataEvidence("data-1", "sales", "SALES_TOTAL", 2,
            "2026-09-25", "{\"data\":{\"complete\":" + complete
                + ",\"rows\":[{\"amount\":20},{\"amount\":22}],\"rowCount\":2}}",
            Map.of("tenantId", tenant));
        return new AnalysisContext("sum sales", new KernelDataScope("tenant", "user", "request",
            null, "run", null, Map.of()), "sales-skill", List.of(), List.of(), List.of(), null,
            Map.of(AnalysisContext.EVIDENCE_BUNDLE_ATTRIBUTE,
                    new EvidenceBundle(null, List.of(source), List.of(), Map.of()),
                VerifiedEvidenceComputationOperator.OPERATION, "SUM",
                VerifiedEvidenceComputationOperator.FIELD, "amount"));
    }

    private AnalysisScope scope() {
        return new AnalysisScope("tenant", "user", List.of(), List.of(), Map.of());
    }
}
