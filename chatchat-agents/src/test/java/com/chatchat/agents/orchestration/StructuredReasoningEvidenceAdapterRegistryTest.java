package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredReasoningEvidenceAdapterRegistryTest {

    private final StructuredReasoningEvidenceAdapterRegistry registry =
        new StructuredReasoningEvidenceAdapterRegistry();

    @Test
    void preservesStructuredRowsAsFactsAndCandidatesAsReferences() {
        Map<String, Object> projection = registry.project(Map.of(
            "runtimeEvidenceSchemaVersion", "structured_data_search_result.v1",
            "structuredDatasetCount", 1,
            "structuredObservationCount", 1,
            "coverageComplete", true,
            "assets", List.of(Map.of("dataset", "bond_quote")),
            "structuredData", List.of(Map.of(
                "dataset", "bond_quote",
                "analysisContext", Map.of(
                    "schemaVersion", "data_analysis_context.v1",
                    "governance", Map.of("protocolVersion", "summary_governance.v1"),
                    "source", Map.of("displayName", "Bond quote observations"),
                    "schema", Map.of("fields", List.of(
                        Map.of("name", "yield", "description", "Yield to maturity")))),
                "count", 1,
                "rows", List.of(Map.of("bond", "240001", "yield", 2.31))
            ))
        ));

        assertThat(projection)
            .containsEntry("evidenceRole", "STRUCTURED_DATA_FACTS")
            .containsEntry("assessmentCapability", "DATASET_RECORD_ANALYSIS");
        assertThat(projection.get("factEvidence").toString()).contains("240001", "2.31");
        assertThat(projection.get("analysisContexts").toString())
            .contains("summary_governance.v1", "Bond quote observations", "Yield to maturity");
        assertThat(projection.get("referenceEvidence").toString()).contains("bond_quote");
        assertThat(projection.get("reasoningRules").toString())
            .contains("summary-governance input", "not observed data", "discovery metadata");
    }

    @Test
    void ddlAnnotationNeverPromotesStandardMatchToCompliance() {
        Map<String, Object> projection = registry.project(Map.of(
            "schemaVersion", "metadata_ddl_annotation.v1",
            "success", true,
            "columns", List.of(Map.of(
                "physical", Map.of("name", "CRED_GRAD", "dataType", "varchar"),
                "standardField", Map.of("technicalName", "CRED_GRAD", "matchScore", 0.97),
                "standardTerms", List.of(),
                "standardDictionaries", List.of(),
                "unmatchedNameTerms", List.of(),
                "confidence", 0.97,
                "annotationStatus", "ANNOTATED"
            ))
        ));

        assertThat(projection.get("assessmentCapability")).isEqualTo("FIELD_SEMANTIC_ANNOTATION_ONLY");
        assertThat(projection.get("reasoningRules").toString())
            .contains("not enterprise-design compliance", "not proof of deployed database state");
        assertThat(projection.get("evidenceCoverage").toString())
            .contains("SOURCE_FACTS_AND_STANDARD_REFERENCES", "field-standard candidate metadata")
            .doesNotContain("primary_key_design", "notAssessedClaims");
    }

    @Test
    void requirementCandidatesStayGroupedByRequirement() {
        Map<String, Object> projection = registry.project(Map.of(
            "schemaVersion", "api_requirement_analysis.v1",
            "requirementCount", 2,
            "allRequirementsHaveCandidates", false,
            "missingRequirementIds", List.of("r2"),
            "coverage", List.of(
                Map.of("requirement", Map.of("id", "r1"), "candidateStatus", "CANDIDATES_FOUND",
                    "returnedCount", 1, "templates", List.of(Map.of("templateId", "t1"))),
                Map.of("requirement", Map.of("id", "r2"), "candidateStatus", "NO_CANDIDATE",
                    "returnedCount", 0, "templates", List.of())
            )
        ));

        assertThat(projection.get("candidateEvidence").toString()).contains("r1", "t1", "r2", "NO_CANDIDATE");
        assertThat(projection.get("candidateCollectionContract").toString())
            .contains("$.coverage[*].templates", "candidateIsAcceptance=false");
    }

    @Test
    void findsRegisteredProtocolInsideOperationalSummaryWrapper() {
        Map<String, Object> projection = registry.project(Map.of(
            "schemaVersion", "tool_result_summary.v1",
            "summaryTruncated", true,
            "preview", Map.of(
                "schemaVersion", "asset_query_result.v1",
                "returnedCount", 1,
                "possiblyTruncated", false,
                "assets", List.of(Map.of("asset", Map.of("id", "asset-1")))
            )
        ));

        assertThat(projection)
            .containsEntry("sourceSchemaVersion", "asset_query_result.v1")
            .containsEntry("evidenceRole", "ROUTING_CANDIDATES");
    }
}
