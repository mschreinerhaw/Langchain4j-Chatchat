package com.chatchat.agents.runtime.federation;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.evidence.ToolAnalysisEvidence;
import com.chatchat.common.runtime.capability.CapabilityId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteAgentEvidenceProjectorTest {
    @Test void domainPackageTransfersSelectedEvidenceButRedactsCredentialFields() {
        var evidence = new EvidenceBundle(null, List.of(
            new DocumentAnalysisEvidence("doc-e1", "doc-1", "chunk-1", "Policy", "risk",
                "Policy#risk", "Risk rule", 0.9, Map.of()),
            new ToolAnalysisEvidence("tool-e1", "position_read", "call-1",
                "{\"customer\":{\"positions\":[1,2],\"authToken\":\"do-not-send\"}}", Map.of())),
            List.of(), Map.of());
        var request = new AgentExecutionRequest(null, "execution-1", CapabilityId.parse("finance.risk.v1"),
            new AgentExecutionRequest.TaskContract("analysis", "Analyze", Map.of()), evidence, Set.of(),
            null, null, new KernelDataScope("tenant-1", "user-1", "req", null, "run", null, Map.of()),
            Map.of(AgentExecutionRequest.DOMAIN_PACKAGE_METADATA_KEY, "analysis_package.v1"));

        var projected = new RemoteAgentEvidenceProjector(new ObjectMapper()).project(request);

        assertThat(projected.evidence().evidence()).hasSize(2);
        assertThat(projected.evidence().evidence().get(0).toString()).contains("Risk rule", "Policy#risk");
        assertThat(projected.evidence().evidence().get(1).toString())
            .contains("positions").doesNotContain("do-not-send", "authToken");
        assertThat(projected.metadata()).doesNotContainKey("localSkillId");
    }
}
