package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.dataset.DatasetHandle;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelEvidenceAssessmentAuditTest {
    private Map<String, Object> assessment(Object record, List<?> path, String source) {
        return Map.of("evidenceStatus", "PARTIAL", "requiresReanalysis", false, "claims", List.of(Map.of(
            "claimId", "C1", "claim", "Model interpretation", "references", List.of(Map.of(
                "datasetReference", source, "record", record, "fieldPath", path)))));
    }
    private String status(Map<String, Object> audit) {
        return String.valueOf(((Map<?, ?>)((List<?>) audit.get("referenceObservations")).get(0)).get("status"));
    }
    @Test void recordsNestedNullFieldWithoutPretendingToValidateTheClaim() {
        var fields = new LinkedHashMap<String, Object>(); fields.put("value", null);
        var source = new Dataset("observations", Map.of(), List.of(Map.of("values", List.of(fields))));
        var input = assessment(1, List.of("values", 0, "value"), "observations");
        var result = new ModelEvidenceAssessmentAudit().record(input, Map.of("observations", source), () -> {});
        assertThat(status(result)).isEqualTo("FIELD_PATH_EXISTS_NOT_SEMANTIC_VALIDATION");
        assertThat(result).containsEntry("publicationEffect", "NONE").containsEntry("authority", "MODEL").containsEntry("assessment", input);
    }
    @Test void reportsInvalidIdentityRecordAndFieldAsObservations() {
        var sources = Map.of("observations", new Dataset("observations", Map.of(), List.of(Map.of("value", 42))));
        var audit = new ModelEvidenceAssessmentAudit();
        assertThat(status(audit.record(assessment(1, List.of("value"), "other-run"), sources, () -> {})))
            .isEqualTo("SOURCE_NOT_IN_CURRENT_WORKSPACE");
        assertThat(status(audit.record(assessment(1.5, List.of("value"), "observations"), sources, () -> {})))
            .isEqualTo("RECORD_NOT_IN_RETURNED_RANGE");
        assertThat(status(audit.record(assessment(1, List.of("missing"), "observations"), sources, () -> {})))
            .isEqualTo("FIELD_PATH_NOT_FOUND");
    }
    @Test void optionalAndOversizedAssessmentNeverBecomesAnAdmissionFailure() {
        var audit = new ModelEvidenceAssessmentAudit();
        assertThat(audit.record(null, Map.of(), () -> {})).containsEntry("status", "NOT_SUPPLIED").containsEntry("publicationEffect", "NONE");
        assertThat(audit.record(Map.of("claim", "a".repeat(32_001)), Map.of(), () -> {}))
            .containsEntry("status", "AUDIT_PAYLOAD_LIMIT").containsEntry("publicationEffect", "NONE");
    }
    @Test void referenceReadFailureIsAnObservationAndCancellationStillStopsExecution() {
        DatasetHandle broken = new DatasetHandle() {
            public long recordCount() { return 1; }
            public Page readPage(long offset, int limit) { throw new IllegalStateException("unavailable"); }
        };
        var input = assessment(1, List.of("value"), "source");
        assertThat(status(new ModelEvidenceAssessmentAudit().record(input, Map.of("source", new Dataset("source", Map.of(), broken)), () -> {})))
            .isEqualTo("REFERENCE_READ_UNAVAILABLE");
        assertThatThrownBy(() -> new ModelEvidenceAssessmentAudit().record(input, Map.of(), () -> {
            throw new java.util.concurrent.CancellationException();
        })).isInstanceOf(java.util.concurrent.CancellationException.class);
    }
}
