package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.protocol.ModelProtocolJson;
import java.util.*;

/** Records model-authored assessments and mechanical reference observations. Never grants
 * tool access, changes prose, selects conclusions, or controls publication. */
final class ModelEvidenceAssessmentAudit {
    static final String SCHEMA_VERSION = "model_evidence_assessment_audit.v1";

    Map<String, Object> record(Object assessment, Map<String, Dataset> sources, Runnable guard) {
        if (!(assessment instanceof Map<?, ?>)) return Map.of("schemaVersion", SCHEMA_VERSION,
            "authority", "MODEL", "status", "NOT_SUPPLIED", "publicationEffect", "NONE");
        String serialized = ModelProtocolJson.compact(assessment);
        if (serialized.length() > 32_000) return Map.of("schemaVersion", SCHEMA_VERSION,
            "authority", "MODEL", "status", "AUDIT_PAYLOAD_LIMIT", "publicationEffect", "NONE");
        List<Map<String, Object>> observations = new ArrayList<>();
        if (((Map<?, ?>) assessment).get("claims") instanceof List<?> claims) {
            for (Object item : claims.stream().limit(100).toList()) {
                if (!(item instanceof Map<?, ?> claim) || !(claim.get("references") instanceof List<?> refs)) continue;
                for (Object raw : refs.stream().limit(16).toList()) {
                    guard.run();
                    if (!(raw instanceof Map<?, ?> ref)) continue;
                    String sourceId = ref.get("datasetReference") instanceof String text ? text : "";
                    String status;
                    try { status = referenceStatus(ref, sources.get(sourceId)); }
                    catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
                    catch (RuntimeException unavailable) { status = "REFERENCE_READ_UNAVAILABLE"; }
                    observations.add(Map.of("claimId", String.valueOf(claim.get("claimId")),
                        "datasetReference", sourceId, "status", status));
                }
            }
        }
        // Round-trip to detach the archived assessment from mutable workspace maps.
        Object snapshot;
        try { snapshot = new com.fasterxml.jackson.databind.ObjectMapper().readValue(serialized, Object.class); }
        catch (java.io.IOException impossible) { throw new IllegalArgumentException("Assessment JSON", impossible); }
        return Map.of("schemaVersion", SCHEMA_VERSION, "authority", "MODEL", "status", "RECORDED",
            "assessment", snapshot, "referenceObservations", List.copyOf(observations),
            "referenceObservationLimit", 1600, "publicationEffect", "NONE",
            "observationCoverage", "FIRST_100_CLAIMS_FIRST_16_REFERENCES_EACH");
    }

    private String referenceStatus(Map<?, ?> ref, Dataset source) {
        if (source == null) return "SOURCE_NOT_IN_CURRENT_WORKSPACE";
        if (!(ref.get("record") instanceof Number record)) return "RECORD_REFERENCE_UNSPECIFIED";
        long ordinal = record.longValue();
        if (record.doubleValue() != ordinal || ordinal < 1 || ordinal > source.recordCount()) return "RECORD_NOT_IN_RETURNED_RANGE";
        if (!(ref.get("fieldPath") instanceof List<?> path) || path.isEmpty() || path.size() > 16) return "FIELD_PATH_UNSPECIFIED";
        var page = source.handle().readPage(ordinal - 1, 1);
        if (page.rows().isEmpty()) return "RECORD_NOT_RETURNED";
        Object value = page.rows().get(0);
        for (Object key : path) {
            if (key instanceof String name && value instanceof Map<?, ?> map && map.containsKey(name)) value = map.get(name);
            else if (key instanceof Number index && value instanceof List<?> list
                && index.doubleValue() == index.intValue() && index.intValue() >= 0 && index.intValue() < list.size()) value = list.get(index.intValue());
            else return "FIELD_PATH_NOT_FOUND";
        }
        return "FIELD_PATH_EXISTS_NOT_SEMANTIC_VALIDATION";
    }
}
