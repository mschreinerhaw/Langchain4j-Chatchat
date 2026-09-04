package com.chatchat.agents.orchestration.analysis.protocol;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisLayerGovernanceContract;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical, source-neutral hand-off protocol for analytical work products.
 *
 * <p>All business Claims cross Worker, Reducer and Driver boundaries through this protocol.
 * Execution diagnostics and prompt/context text are deliberately not representable as business
 * artifacts. Legacy evidence channels are normalized at the boundary for rolling upgrades.</p>
 */
public final class AnalysisArtifactProtocol {

    public static final String SCHEMA_VERSION = "analysis_artifact.v1";
    public static final String EVIDENCE_KEY = "analysisArtifacts";
    private static final Set<String> PUBLISHABLE_STATUSES = Set.of(
        "SUPPORTED", "PARTIAL", "REVIEW_REQUIRED");

    private AnalysisArtifactProtocol() {
    }

    public static List<Map<String, Object>> normalize(AnalysisSummaryResult summary) {
        if (summary == null || summary.evidence() == null) return List.of();
        List<Map<String, Object>> declared = maps(summary.evidence().get(EVIDENCE_KEY));
        if (!declared.isEmpty()) return declared.stream()
            .map(AnalysisArtifactProtocol::validated)
            .filter(java.util.Objects::nonNull).toList();
        return fromLegacy(summary);
    }

    public static List<Map<String, Object>> collect(Collection<AnalysisSummaryResult> summaries) {
        LinkedHashMap<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (summaries != null) {
            for (AnalysisSummaryResult summary : summaries) {
                for (Map<String, Object> artifact : normalize(summary)) {
                    result.putIfAbsent(text(artifact.get("artifactId")), artifact);
                }
            }
        }
        return List.copyOf(result.values());
    }

    public static List<Map<String, Object>> fromEvidence(String sourceStage, String sourceScope,
                                                   Map<String, Object> evidence) {
        if (evidence == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> claim : maps(evidence.get("observedFactClaims"))) {
            add(result, sourceStage, sourceScope, text(claim.get("claimId")),
                "OBSERVED_RETURNED_FACT", text(claim.get("claim")), "SUPPORTED",
                text(claim.get("confidence")), text(claim.get("significance")),
                strings(claim.get("recordRefs")), strings(claim.get("supportingValues")),
                List.of(), strings(claim.get("caveats")), List.of());
        }
        Set<String> publishableInsightIds = publishableInsightIds(evidence);
        boolean decisionsDeclared = !maps(evidence.get("claimAdmissionDecisions")).isEmpty();
        for (Map<String, Object> claim : maps(evidence.get("insights"))) {
            String claimId = text(claim.get("claimId"));
            if (decisionsDeclared && !publishableInsightIds.contains(claimId)) continue;
            add(result, sourceStage, sourceScope, claimId, text(claim.get("claimClass")),
                text(claim.get("claim")), text(claim.get("governanceStatus")),
                text(claim.get("confidence")), text(claim.get("significance")),
                strings(claim.get("recordRefs")), strings(claim.get("supportingValues")),
                List.of(), strings(claim.get("caveats")), strings(claim.get("reviewReasons")));
        }
        for (Map<String, Object> item : maps(evidence.get("analysisItems"))) {
            String status = text(item.get("status")).toUpperCase(java.util.Locale.ROOT);
            if (!PUBLISHABLE_STATUSES.contains(status)) continue;
            add(result, sourceStage, sourceScope, "", "GOVERNED_ANALYSIS_ITEM",
                text(item.get("finding")), status, text(item.get("confidence")),
                text(item.get("businessMeaning")), strings(item.get("basisRecordRefs")),
                strings(item.get("supportingValues")), List.of(),
                strings(item.get("limitations")), "REVIEW_REQUIRED".equals(status)
                    ? strings(item.get("limitations")) : List.of());
        }
        LinkedHashMap<String, Map<String, Object>> distinct = new LinkedHashMap<>();
        result.forEach(item -> {
            String key = "OBSERVED_RETURNED_FACT".equals(text(item.get("claimClass")))
                ? "observed:" + DataAnalysisLayerGovernanceContract.fingerprint(List.of(
                    text(item.get("sourceScope")), strings(item.get("recordRefs")).stream().sorted().toList(),
                    strings(item.get("supportingValues")).stream().sorted().toList()))
                : text(item.get("artifactId"));
            // Insights follow generated observed-fact Claims and therefore retain the model's
            // richer significance/caveat fields for identical evidence.
            distinct.put(key, item);
        });
        return List.copyOf(distinct.values());
    }

    public static Map<String, Object> derived(String sourceStage, String sourceScope, String artifactId,
                                       String text, String status, String confidence,
                                       String significance, List<String> basisClaimIds,
                                       List<String> recordRefs, List<String> supportingValues,
                                       List<String> caveats) {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        String governedStatus = status == null || status.isBlank() ? "REVIEW_REQUIRED" : status;
        add(artifacts, sourceStage, sourceScope, artifactId, sourceStage + "_DERIVED_CLAIM",
            text, governedStatus, confidence, significance, recordRefs, supportingValues,
            basisClaimIds, caveats, caveats);
        return artifacts.isEmpty() ? Map.of() : artifacts.get(0);
    }

    private static List<Map<String, Object>> fromLegacy(AnalysisSummaryResult summary) {
        return fromEvidence(stage(summary), sourceScope(summary), summary.evidence());
    }

    private static void add(List<Map<String, Object>> target, String sourceStage,
                            String sourceScope, String requestedId, String claimClass,
                            String claimText, String requestedStatus, String confidence,
                            String significance, List<String> recordRefs,
                            List<String> supportingValues, List<String> basisClaimIds,
                            List<String> caveats, List<String> reviewReasons) {
        if (claimText.isBlank() || recordRefs.isEmpty() || supportingValues.isEmpty()) return;
        String status = requestedStatus == null ? "" : requestedStatus.toUpperCase(java.util.Locale.ROOT);
        if (!PUBLISHABLE_STATUSES.contains(status)) status = "SUPPORTED";
        String id = requestedId == null ? "" : requestedId.trim();
        if (id.isBlank()) {
            id = "artifact:" + DataAnalysisLayerGovernanceContract.fingerprint(List.of(
                sourceStage, sourceScope, claimClass, claimText,
                recordRefs.stream().sorted().toList(), supportingValues.stream().sorted().toList(),
                basisClaimIds.stream().sorted().toList()));
        }
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("schemaVersion", SCHEMA_VERSION);
        artifact.put("artifactId", id);
        artifact.put("artifactType", "BUSINESS_CLAIM");
        artifact.put("sourceStage", sourceStage == null ? "WORKER" : sourceStage);
        artifact.put("sourceScope", sourceScope == null ? "" : sourceScope);
        artifact.put("claimClass", claimClass == null || claimClass.isBlank()
            ? "GOVERNED_ANALYSIS_ITEM" : claimClass);
        artifact.put("text", claimText);
        artifact.put("status", status);
        artifact.put("confidence", confidence == null ? "" : confidence);
        artifact.put("significance", significance == null ? "" : significance);
        artifact.put("recordRefs", List.copyOf(recordRefs));
        artifact.put("supportingValues", List.copyOf(supportingValues));
        artifact.put("basisClaimIds", List.copyOf(basisClaimIds));
        artifact.put("caveats", List.copyOf(caveats));
        artifact.put("reviewReasons", List.copyOf(reviewReasons));
        target.add(Collections.unmodifiableMap(artifact));
    }

    private static Map<String, Object> validated(Map<String, Object> source) {
        if (!SCHEMA_VERSION.equals(text(source.get("schemaVersion")))
            || !"BUSINESS_CLAIM".equals(text(source.get("artifactType")))) return null;
        String id = text(source.get("artifactId"));
        String claim = text(source.get("text"));
        List<String> refs = strings(source.get("recordRefs"));
        List<String> values = strings(source.get("supportingValues"));
        String status = text(source.get("status")).toUpperCase(java.util.Locale.ROOT);
        if (id.isBlank() || claim.isBlank() || refs.isEmpty() || values.isEmpty()
            || !PUBLISHABLE_STATUSES.contains(status)) return null;
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Set<String> publishableInsightIds(Map<String, Object> evidence) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> decision : maps(evidence.get("claimAdmissionDecisions"))) {
            if (Boolean.TRUE.equals(decision.get("admitted"))
                || Boolean.TRUE.equals(decision.get("reviewRequired"))) {
                String id = text(decision.get("claimId"));
                if (!id.isBlank()) ids.add(id);
            }
        }
        return Set.copyOf(ids);
    }

    private static String stage(AnalysisSummaryResult summary) {
        String stage = text(summary.evidence().get("analysisParticipantRole"));
        return stage.isBlank() ? "WORKER" : stage;
    }

    private static String sourceScope(AnalysisSummaryResult summary) {
        for (String key : List.of("datasetReference", "groupId", "scope")) {
            String value = text(summary.position().get(key));
            if (!value.isBlank()) return value;
        }
        return summary.scope();
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : collection) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> {
                if (key != null) copy.put(String.valueOf(key), entry);
            });
            result.add(copy);
        }
        return List.copyOf(result);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().filter(java.util.Objects::nonNull).map(String::valueOf)
            .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
