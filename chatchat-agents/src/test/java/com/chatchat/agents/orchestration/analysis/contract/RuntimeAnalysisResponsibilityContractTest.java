package com.chatchat.agents.orchestration.analysis.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeAnalysisResponsibilityContractTest {

    @Test
    void keepsDomainReasoningOutsideRuntimeWhilePreservingEvidenceTransport() {
        assertThat(RuntimeAnalysisResponsibilityContract.promptSection())
            .contains("must not author domain conclusions", "The model owns analytical reasoning",
                "active Agent role", "domain knowledge to choose useful comparisons",
                "not a requirement to expose a confidence label",
                "rather than from a Runtime hard-coded example",
                "does not censor supported analytical breadth",
                "observed facts", "derived indicators", "cross-validation",
                "pattern recognition", "business interpretation", "hypotheses",
                "scenario analysis", "risks", "data gaps", "next actions",
                "not directly proven", "analysis forbidden",
                "Optional analytical lenses", "not a mandatory sequence",
                "Design the report and reasoning yourself", "complete report body",
                "report sections, prose, tables",
                "Cannot prove", "cannot analyze")
            .doesNotContain("Securities market value", "investment preference", "99.89%",
                "at least one material alternative explanation");
    }
}
