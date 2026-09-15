package com.chatchat.agents.orchestration.analysis.loop;

import com.chatchat.agents.assessment.EvidenceGrade;
import com.chatchat.agents.assessment.TaskContract;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Computes one business-agnostic evidence grade from the complete iteration history. */
public final class EvidenceCoverageAssessment {

    public Result assess(List<Map<String, Object>> history, Map<String, Object> metadata) {
        List<Map<String, Object>> snapshots = history == null
            ? List.of() : history.stream().filter(java.util.Objects::nonNull).toList();
        Map<String, Object> latest = snapshots.isEmpty()
            ? Map.of() : snapshots.get(snapshots.size() - 1);
        List<Map<String, Object>> usable = snapshots.stream()
            .flatMap(snapshot -> maps(snapshot.get("toolEvidence")).stream())
            .filter(this::usableEvidenceItem)
            .toList();
        List<Requirement> requirements = requirements(latest, metadata);
        List<String> missingRequired = requirements.stream()
            .filter(item -> item.importance() == TaskContract.EvidenceImportance.REQUIRED)
            .filter(item -> usable.stream().noneMatch(evidence -> matches(item, evidence)))
            .map(Requirement::id)
            .toList();
        // A governed worker/reducer summary is evidence: it was produced by binding, validating and
        // reviewing successfully-retrieved records. Treating it as usable keeps a run that retrieved
        // and analysed data from collapsing to "no available evidence" just because structured claim
        // admission published nothing; the report then degrades to ANALYZE_WITH_LIMITATIONS.
        boolean governedSummaryAvailable = governedAnalysisSummaryPresent(metadata);
        boolean evidenceAvailable = !usable.isEmpty() || governedSummaryAvailable;
        boolean latestSufficient = truthy(latest.get("sufficient"));
        boolean conflictsRemain = size(latest.get("conflicts")) > 0;
        boolean gapsRemain = size(latest.getOrDefault(
            "remainingMissing", latest.get("missingEvidence"))) > 0;
        TaskContract.EvidenceRequirement taskRequirement = taskRequirement(metadata);
        boolean requiredEvidenceMissing = !missingRequired.isEmpty()
            || (taskRequirement != TaskContract.EvidenceRequirement.OPTIONAL
                && !evidenceAvailable);
        EvidenceGrade grade = latestSufficient && !requiredEvidenceMissing
            ? EvidenceGrade.SUFFICIENT
            : evidenceAvailable
                ? (requiredEvidenceMissing || conflictsRemain
                    ? EvidenceGrade.LIMITED : EvidenceGrade.PARTIAL_USABLE)
                : EvidenceGrade.INSUFFICIENT;
        return new Result(grade, evidenceAvailable, requiredEvidenceMissing,
            missingRequired, requirements.size(), Math.max(usable.size(), governedSummaryAvailable ? 1 : 0),
            gapsRemain, conflictsRemain);
    }

    private List<Requirement> requirements(Map<String, Object> latest,
                                           Map<String, Object> metadata) {
        Map<String, Requirement> result = new LinkedHashMap<>();
        for (Map<String, Object> item : maps(latest.get("evidenceRequirements"))) {
            Requirement requirement = requirement(item);
            if (requirement != null) result.put(requirement.id(), requirement);
        }
        Object contractValue = metadata == null ? null : metadata.get("taskContract");
        if (contractValue instanceof TaskContract contract) {
            for (TaskContract.EvidenceItem item : contract.evidenceItems()) {
                if (item == null) continue;
                Requirement requirement = new Requirement(item.id(), item.sourceStepId(),
                    item.sourceTool(), item.importance());
                result.put(requirement.id(), requirement);
            }
        }
        return List.copyOf(result.values());
    }

    private Requirement requirement(Map<String, Object> item) {
        if (item == null) return null;
        Integer stepId = integer(item.get("sourceStepId"));
        String tool = text(item.get("sourceTool"));
        String id = text(item.get("id"));
        if (id.isBlank()) id = stepId == null ? tool : "step:" + stepId;
        if (id.isBlank()) return null;
        TaskContract.EvidenceImportance importance;
        try {
            importance = TaskContract.EvidenceImportance.valueOf(
                text(item.get("importance")).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            importance = TaskContract.EvidenceImportance.IMPORTANT;
        }
        return new Requirement(id, stepId, tool, importance);
    }

    private boolean matches(Requirement requirement, Map<String, Object> evidence) {
        Integer stepId = integer(evidence.get("stepId"));
        String tool = text(evidence.get("tool"));
        return (requirement.sourceStepId() != null && requirement.sourceStepId().equals(stepId))
            || (!requirement.sourceTool().isBlank() && requirement.sourceTool().equals(tool));
    }

    private boolean usableEvidenceItem(Map<String, Object> item) {
        return item != null
            && !Boolean.FALSE.equals(item.get("success"))
            && (meaningful(item.get("outputFacts")) || meaningful(item.get("output")));
    }

    private TaskContract.EvidenceRequirement taskRequirement(Map<String, Object> metadata) {
        Object contract = metadata == null ? null : metadata.get("taskContract");
        if (contract instanceof TaskContract value) return value.evidenceRequirement();
        String configured = text(metadata == null ? null : metadata.get("evidenceRequirement"));
        try {
            return configured.isBlank() ? TaskContract.EvidenceRequirement.OPTIONAL
                : TaskContract.EvidenceRequirement.valueOf(configured.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return TaskContract.EvidenceRequirement.OPTIONAL;
        }
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> source)) continue;
            Map<String, Object> converted = new LinkedHashMap<>();
            source.forEach((key, entry) -> {
                if (key != null) converted.put(String.valueOf(key), entry);
            });
            result.add(java.util.Collections.unmodifiableMap(converted));
        }
        return List.copyOf(result);
    }

    private boolean governedAnalysisSummaryPresent(Map<String, Object> metadata) {
        if (metadata == null) return false;
        // These counters are written by the reducer governance stage once governed worker/reducer
        // reports have been reviewed over successfully-retrieved records.
        if (positive(metadata.get("analysisReducerReviewableReportCount"))
            || positive(metadata.get("analysisReducerAdmittedReportCount"))) {
            return true;
        }
        String barrier = text(metadata.get("analysisSynthesisBarrierStatus"));
        return barrier.equals("READY") || barrier.equals("READY_WITH_REDUCER_REVIEW_NOTES");
    }

    private boolean positive(Object value) {
        Integer parsed = integer(value);
        return parsed != null && parsed > 0;
    }

    private boolean meaningful(Object value) {
        if (value == null) return false;
        if (value instanceof CharSequence text) {
            String normalized = text.toString().trim();
            return !normalized.isEmpty() && !"null".equalsIgnoreCase(normalized)
                && !"[]".equals(normalized) && !"{}".equals(normalized);
        }
        if (value instanceof Map<?, ?> map)
            return !map.isEmpty() && map.values().stream().anyMatch(this::meaningful);
        if (value instanceof Iterable<?> values) return values.iterator().hasNext();
        if (value.getClass().isArray()) return Array.getLength(value) > 0;
        return true;
    }

    private int size(Object value) {
        if (value instanceof Collection<?> collection) return collection.size();
        if (value instanceof Map<?, ?> map) return map.size();
        return value == null || String.valueOf(value).isBlank() ? 0 : 1;
    }

    private boolean truthy(Object value) {
        return value instanceof Boolean flag ? flag
            : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record Requirement(String id, Integer sourceStepId, String sourceTool,
                               TaskContract.EvidenceImportance importance) {
        private Requirement {
            sourceTool = sourceTool == null ? "" : sourceTool;
        }
    }

    public record Result(EvidenceGrade grade, boolean evidenceAvailable,
                         boolean requiredEvidenceMissing, List<String> missingRequiredEvidenceItems,
                         int requirementCount, int usableEvidenceCount,
                         boolean gapsRemain, boolean conflictsRemain) {
        public Result {
            missingRequiredEvidenceItems = missingRequiredEvidenceItems == null
                ? List.of() : List.copyOf(missingRequiredEvidenceItems);
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "grade", grade.name(),
                "evidenceAvailable", evidenceAvailable,
                "requiredEvidenceMissing", requiredEvidenceMissing,
                "missingRequiredEvidenceItems", missingRequiredEvidenceItems,
                "requirementCount", requirementCount,
                "usableEvidenceCount", usableEvidenceCount,
                "gapsRemain", gapsRemain,
                "conflictsRemain", conflictsRemain);
        }
    }
}
