package com.chatchat.agents.orchestration.analysis.contract;

import com.chatchat.common.runtime.summary.analysis.model.DataAnalysisPosition;
import com.chatchat.common.runtime.summary.analysis.contract.AnalysisMethodologyContract;
import com.chatchat.common.runtime.summary.analysis.contract.ProfessionalAnalysisDepthContract;
import com.chatchat.common.runtime.summary.analysis.contract.ProfessionalDataAnalysisContract;

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
        List<String> businessQuestions = new ArrayList<>();
        businessQuestions.addAll(strings(currentTemplate.get("matchedQuestionAspects")));
        businessQuestions.addAll(strings(intent.get("analysisFocus")));
        businessQuestions.addAll(strings(intent.get("metrics")));
        contract.put("analysisPlan", Map.of(
            "schemaVersion", "business_analysis_plan.v1",
            "primaryGoal", originalQuestion.trim(),
            "businessQuestions", businessQuestions.stream().distinct().map(question -> Map.of(
                "questionId", "q-" + Integer.toUnsignedString(question.hashCode(), 36),
                "question", question,
                "criticality", "CORE"
            )).toList()
        ));
        contract.put("analysisAgenda", analysisAgenda(originalQuestion, intent,
            businessQuestions));
        contract.put("professionalAnalysisContract",
            ProfessionalDataAnalysisContract.enterpriseDefault().toMap());
        contract.put("professionalAnalysisDepthContract",
            ProfessionalAnalysisDepthContract.enterpriseDefault().toMap());
        contract.put("analysisMethodologyContract",
            AnalysisMethodologyContract.enterpriseDefault().toMap());
        contract.put("analysisTree", analysisTree(originalQuestion, businessQuestions,
            strings(intent.get("dimensions"))));
        contract.put("workerObligations", List.of(
            "ANSWER_ONLY_OBJECTIVE_RELEVANT_ASPECTS",
            "PRESERVE_PRODUCER_DECLARED_FIELD_SEMANTICS",
            "DO_NOT_INFER_UNDECLARED_AGGREGATION_OR_RELATIONSHIPS",
            "SEPARATE_OBSERVED_SCOPE_FROM_EXPLICITLY_DECLARED_SCOPE",
            "REPORT_UNSUPPORTED_OBJECTIVE_ASPECTS",
            "PRESERVE_EXACT_VALUES_AND_RECORD_REFERENCES",
            "COMPLETE_EVERY_PROFESSIONAL_ANALYSIS_STAGE",
            "CLASSIFY_THE_OBJECTIVE_MODE_AND_SATISFY_ITS_MINIMUM_REASONING_DEPTH",
            "TURN_EACH_UNSUPPORTED_REQUIRED_DEPTH_DIMENSION_INTO_A_DECLARATIVE_EVIDENCE_GAP",
            "SEPARATE_OBSERVATION_DERIVATION_AND_INFERENCE",
            "COMPLETE_DYNAMIC_ANALYSIS_AGENDA_BEFORE_REPORTING_GAPS",
            "BIND_EACH_SUPPORTED_ANALYSIS_ITEM_TO_FACTS_OR_CLAIMS",
            "EXECUTE_THE_ANALYSIS_TREE_USING_TOTAL_TO_COMPONENT_TO_DRIVER_REASONING",
            "DECLARE_THE_BASELINE_OR_LIMIT_ONLY_BASELINE_DEPENDENT_CLAIMS",
            "RANK_FINDINGS_BY_RELEVANCE_MATERIALITY_AND_CONFIDENCE",
            "CALIBRATE_CONCLUSION_STRENGTH_TO_EVIDENCE_SCOPE"));
        return Collections.unmodifiableMap(contract);
    }

    private Map<String, Object> analysisTree(String originalQuestion,
                                             List<String> businessQuestions,
                                             List<String> dimensions) {
        List<Map<String, Object>> children = new ArrayList<>();
        List<String> questions = businessQuestions.isEmpty()
            ? List.of("Establish the overall state", "Identify material components and contributors",
                "Explain supported drivers and business impact")
            : businessQuestions;
        int index = 1;
        for (String question : questions.stream().distinct().limit(12).toList()) {
            children.add(Map.of(
                "questionId", "Q" + index++,
                "question", question,
                "candidateDimensions", dimensions,
                "requiredAnswer", "FINDING_WITH_EVIDENCE_OR_SCOPED_LIMITATION"));
        }
        return Map.of(
            "schemaVersion", "analysis_tree.v1",
            "root", Map.of("questionId", "Q0", "question", originalQuestion.trim()),
            "children", List.copyOf(children),
            "decompositionPolicy", "MECE_WHERE_POSSIBLE",
            "reasoningOrder", List.of("TOTAL", "COMPONENT", "CONTRIBUTION", "DRIVER", "IMPACT"));
    }

    private Map<String, Object> analysisAgenda(String originalQuestion,
                                               Map<String, Object> intent,
                                               List<String> businessQuestions) {
        List<Map<String, Object>> items = new ArrayList<>();
        addItem(items, "CURRENT_STATE", originalQuestion, strings(intent.get("metrics")),
            strings(intent.get("dimensions")), List.of());
        if (!strings(intent.get("dimensions")).isEmpty()) {
            addItem(items, "STRUCTURE_AND_DISTRIBUTION",
                "Analyze composition, distribution and concentration across requested dimensions",
                strings(intent.get("metrics")), strings(intent.get("dimensions")), List.of());
        }
        if (!strings(intent.get("metrics")).isEmpty()) {
            addItem(items, "PERFORMANCE_AND_CONTRIBUTION",
                "Analyze material metric levels, outcomes, contribution and exceptions",
                strings(intent.get("metrics")), strings(intent.get("dimensions")), List.of());
        }
        for (String focus : strings(intent.get("analysisFocus"))) {
            addItem(items, "BEHAVIOR_OR_PATTERN", focus, strings(intent.get("metrics")),
                strings(intent.get("dimensions")), List.of());
        }
        for (String relationship : strings(intent.get("expectedRelationships"))) {
            addItem(items, "CROSS_METRIC_OR_DATASET_RELATIONSHIP", relationship,
                strings(intent.get("metrics")), strings(intent.get("dimensions")),
                List.of(relationship));
        }
        for (String question : businessQuestions) {
            addItem(items, "REQUIREMENT_SPECIFIC", question, strings(intent.get("metrics")),
                strings(intent.get("dimensions")), List.of());
        }
        List<Map<String, Object>> distinct = items.stream()
            .collect(java.util.stream.Collectors.toMap(
                item -> String.valueOf(item.get("itemId")), item -> item,
                (left, right) -> left,
                LinkedHashMap::new)).values().stream().toList();
        return Map.of(
            "schemaVersion", "dynamic_analysis_agenda.v1",
            "completionPolicy", "SUPPORTED_FIRST_ADVISORY_GAPS_LAST",
            "items", distinct);
    }

    private void addItem(List<Map<String, Object>> items, String type, String question,
                         List<String> metrics, List<String> dimensions,
                         List<String> relationships) {
        if (question == null || question.isBlank()) return;
        String signature = type + "|" + question.trim();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", "analysis-item-" + Integer.toUnsignedString(signature.hashCode(), 36));
        item.put("analysisType", type);
        item.put("question", question.trim());
        item.put("candidateMetrics", metrics);
        item.put("candidateDimensions", dimensions);
        item.put("expectedRelationships", relationships);
        item.put("requiredDisposition", "SUPPORTED|PARTIAL|NOT_APPLICABLE|REVIEW_REQUIRED");
        items.add(Collections.unmodifiableMap(item));
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
