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
            .contains("Canonical evidence store (contractVersion=evidence_canonical_v1)")
            .contains("evidenceId: evidence:1")
            .contains("rawContent:")
            .contains("normalizedContent:")
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
            .contains("sourceRef: doc://file-1#chunk=4")
            .contains("evidenceGrade: A")
            .contains("threshold is 1bp");
    }

    @Test
    void canonicalStoreMarksSqlEvidence() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "results", List.of(Map.of(
                "fileId", "file-1",
                "fileName", "ads.sql",
                "chunkIndex", 0,
                "content", "select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i where data_date = 20260101"
            ))
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("Canonical evidence store (contractVersion=evidence_canonical_v1)")
            .contains("Evidence graph execution (contractVersion=evidence_graph_v1)")
            .contains("Evidence OS execution (contractVersion=evidence_os_execution_v2)")
            .contains("Deterministic answer lock (contractVersion=evidence_execution_contract_v2_2)")
            .contains("decision: ANSWER_ALLOWED")
            .contains("contractHash:")
            .contains("graphViewHash:")
            .contains("---BEGIN_LOCKED_ANSWER---")
            .contains("---END_LOCKED_ANSWER---")
            .contains("answerContract: evidence_answer_contract_v2")
            .contains("Valid evidence paths:")
            .contains("type: SQL")
            .contains("type: TRUSTED_SQL")
            .contains("executionVerified: true")
            .contains("sqlLineage: gdp_ads.ads_ids_sys_data_qlty_rpt_d_i")
            .contains("sourceRef: doc://file-1#chunk=0")
            .contains("select * from gdp_ads.ads_ids_sys_data_qlty_rpt_d_i");
    }

    @Test
    void documentVisibilityConstraintFiltersUnselectedEvidenceBeforeObservation() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "selectedDocumentIds", List.of("doc-allowed"),
            "documentVisibilityEnforced", true,
            "results", List.of(
                Map.of(
                    "fileId", "doc-allowed",
                    "fileName", "allowed.docx",
                    "chunkIndex", 0,
                    "content", "visible selected document evidence"
                ),
                Map.of(
                    "fileId", "doc-blocked",
                    "fileName", "blocked.docx",
                    "chunkIndex", 0,
                    "content", "blocked unselected document evidence"
                )
            )
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .contains("Document visibility constraint (contractVersion=document_visibility_v1)")
            .contains("allowedDocuments=1")
            .contains("discardedEvidence=1")
            .contains("visible selected document evidence")
            .doesNotContain("blocked unselected document evidence");
    }

    @Test
    void superAdminBypassesDocumentVisibilityFilteringInObservation() {
        ToolOutput output = ToolOutput.success(Map.of(
            "contractVersion", "document_evidence_v1",
            "selectedDocumentIds", List.of("doc-allowed"),
            "documentVisibilityEnforced", true,
            "roles", List.of("ROLE_SUPER_ADMIN"),
            "results", List.of(
                Map.of(
                    "fileId", "doc-allowed",
                    "fileName", "allowed.docx",
                    "chunkIndex", 0,
                    "content", "visible selected document evidence"
                ),
                Map.of(
                    "fileId", "doc-blocked",
                    "fileName", "blocked.docx",
                    "chunkIndex", 0,
                    "content", "super admin can inspect unselected document evidence"
                )
            )
        ), "ok");

        String observation = builder.buildSuccessObservation("document_search", output, "");

        assertThat(observation)
            .doesNotContain("Document visibility constraint (contractVersion=document_visibility_v1)")
            .contains("visible selected document evidence")
            .contains("super admin can inspect unselected document evidence");
    }
}
