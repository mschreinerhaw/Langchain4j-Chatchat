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
                "does not censor supported analytical breadth")
            .doesNotContain("Securities market value", "investment preference", "99.89%",
                "at least one material alternative explanation");
    }
}
