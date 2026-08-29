package com.chatchat.agents.orchestration.analysis;

import com.chatchat.common.runtime.summary.DataAnalysisPosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles immutable question-to-dataset guidance for every analysis Worker. */
public final class AnalysisObjectiveContractCompiler {

    public static final String SCHEMA_VERSION = "analysis_objective_contract.v1";

    public Map<String, Object> compile(String originalQuestion,
                                       DataAnalysisPosition position,
                                       Map<String, Object> analysisContext) {
        if (originalQuestion == null || originalQuestion.isBlank()) {
            throw new IllegalArgumentException("original user question is required");
        }
        Map<String, Object> context = map(analysisContext);
        Map<String, Object> worker = map(context.get("workerAnalysisContext"));
        Map<String, Object> currentTemplate = map(worker.get("currentTemplate"));
        Map<String, Object> intent = map(worker.get("businessIntent"));
        if (intent.isEmpty()) {
            intent = map(map(context.get("templateMatchAnalysis")).get("analysisIntent"));
        }
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("schemaVersion", SCHEMA_VERSION);
        contract.put("originalQuestion", originalQuestion.trim());
        contract.put("datasetReference", position.datasetReference());
        contract.put("recordScope", position.toMap());
        put(contract, "templateId", currentTemplate.get("templateId"));
        put(contract, "analysisRole", currentTemplate.get("analysisRole"));
        contract.put("matchedQuestionAspects", strings(currentTemplate.get("matchedQuestionAspects")));
        contract.put("metrics", strings(intent.get("metrics")));
        contract.put("dimensions", strings(intent.get("dimensions")));
        contract.put("analysisFocus", strings(intent.get("analysisFocus")));
        contract.put("expectedRelationships", strings(intent.get("expectedRelationships")));
        contract.put("workerObligations", List.of(
            "ANSWER_ONLY_OBJECTIVE_RELEVANT_ASPECTS",
            "PRESERVE_PRODUCER_DECLARED_FIELD_SEMANTICS",
            "DO_NOT_INFER_UNDECLARED_AGGREGATION_OR_RELATIONSHIPS",
            "SEPARATE_OBSERVED_SCOPE_FROM_EXPLICITLY_DECLARED_SCOPE",
            "REPORT_UNSUPPORTED_OBJECTIVE_ASPECTS",
            "PRESERVE_EXACT_VALUES_AND_RECORD_REFERENCES"));
        return Collections.unmodifiableMap(contract);
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null && item != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<String> result = new ArrayList<>();
        iterable.forEach(item -> {
            if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item));
        });
        return result.stream().distinct().toList();
    }
}
