package com.chatchat.agents.orchestration;

import com.chatchat.common.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolObservationBuilderEvidenceTest {

    private final ToolObservationBuilder builder = new ToolObservationBuilder(new EvidenceTrustEvaluator());

    @Test
    void includesUnifiedEvidenceContextForDocumentSearch() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "results", List.of(Map.of(
                "fileId", "file-1",
                "fileName", "manual.pdf",
                "chunkIndex", 3,
                "content", "restart the service after changing config"
            ))
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("Unified evidence context (contractVersion=evidence_v1)")
            .contains("type: DOCUMENT")
            .contains("citation: doc://file-1#chunk=3")
            .contains("Evidence audit: toolName=document_search");
    }

    @Test
    void includesUnifiedEvidenceContextForWebSearch() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "web_evidence_v1",
            "results", List.of(Map.of(
                "title", "Example",
                "url", "https://example.com/a",
                "snippet", "external verification"
            ))
        ), "ok");

        String observation = builder.buildSuccessObservation("web_search", output, "");

        assertThat(observation)
            .contains("Unified evidence context (contractVersion=evidence_v1)")
            .contains("type: WEB")
            .contains("citation: web://example.com/a#result=1")
            .contains("Web search summary");
    }

    @Test
    void includesUnifiedEvidenceContextForDocumentExpandEvidenceChunks() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "evidenceChunks", List.of(Map.of(
                "fileId", "file-1",
                "fileName", "manual.pdf",
                "chunkIndex", 4,
                "text", "threshold is 1bp",
                "evidenceGrade", "A"
            ))
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("citation: doc://file-1#chunk=4")
            .contains("evidenceGrade: A")
            .contains("threshold is 1bp");
    }
}
