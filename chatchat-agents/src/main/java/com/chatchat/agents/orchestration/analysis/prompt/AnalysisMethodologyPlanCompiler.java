package com.chatchat.agents.orchestration.analysis.prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Binds a synthesized methodology to producer-declared fields and capability-supported methods. */
final class AnalysisMethodologyPlanCompiler {
    static final String SCHEMA_VERSION = "bound_analysis_plan.v1";
    private static final int MAX_SUB_QUESTIONS = 16;
    private static final Set<String> KNOWN_METHODS = Set.of(
        "OBSERVE", "BASELINE", "COMPARE", "DECOMPOSE", "CONTRIBUTION", "RANK",
        "TREND", "DISTRIBUTION", "CORRELATION", "CROSS_VALIDATE", "EXPLAIN", "ASSESS_IMPACT");

    Compiled compile(Map<String, Object> planned, Map<String, Object> planningInput) {
        Map<String, Object> capabilities = map(planningInput.get("dataCapabilities"));
        Set<String> supported = new LinkedHashSet<>();
        strings(capabilities.get("supportedMethodology"))
            .forEach(value -> supported.add(value.toUpperCase(Locale.ROOT)));
        supported.add("OBSERVE");

        List<String> requested = strings(planned.get("methodology")).stream()
            .map(value -> value.toUpperCase(Locale.ROOT)).distinct().toList();
        requested.stream().filter(method -> !KNOWN_METHODS.contains(method)).findFirst().ifPresent(method -> {
            throw new IllegalArgumentException("Unsupported dynamic prompt enum: " + method);
        });
        List<String> accepted = requested.stream().filter(supported::contains).toList();
        List<String> rejected = requested.stream().filter(method -> !supported.contains(method)).toList();
        if (accepted.isEmpty()) accepted = List.of("OBSERVE");

        List<Map<String, Object>> subQuestions = new ArrayList<>();
        addModelPlan(subQuestions, planned.get("analysisPlan"), accepted, capabilities);
        addObjectiveTrees(subQuestions, planningInput.get("datasets"), accepted, capabilities);
        if (subQuestions.isEmpty()) {
            subQuestions.add(Map.of(
                "question", text(planningInput.get("userQuestion"), "Analyze the returned evidence"),
                "method", accepted.get(0),
                "targetFields", List.of(),
                "baseline", "NONE_DECLARED"));
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schemaVersion", SCHEMA_VERSION);
        plan.put("bindingPolicy", "EXACT_DECLARED_FIELD_IDENTITY_ONLY");
        plan.put("subQuestions", List.copyOf(subQuestions.subList(0, Math.min(MAX_SUB_QUESTIONS, subQuestions.size()))));
        return new Compiled(List.copyOf(accepted), List.copyOf(rejected), Map.copyOf(plan));
    }

    private void addModelPlan(List<Map<String, Object>> target, Object rawPlan, List<String> methods,
                              Map<String, Object> capabilities) {
        for (Map<String, Object> item : maps(map(rawPlan).get("subQuestions"))) {
            String question = text(item.get("question"), "");
            String method = text(item.get("method"), "").toUpperCase(Locale.ROOT);
            String datasetReference = text(item.get("datasetReference"), "");
            if (question.isBlank() || !methods.contains(method)
                || !methodSupportedForDataset(method, datasetReference, capabilities)) continue;
            List<String> fields = resolveFields(strings(item.get("targetFields")), datasetReference, capabilities);
            target.add(subQuestion(question, method, fields, datasetReference,
                baseline(item.get("baseline"), datasetReference, capabilities)));
        }
    }

    private void addObjectiveTrees(List<Map<String, Object>> target, Object datasets,
                                   List<String> methods, Map<String, Object> capabilities) {
        for (Map<String, Object> dataset : maps(datasets)) {
            String reference = datasetReference(dataset);
            Map<String, Object> objective = map(dataset.get("objective"));
            Map<String, Object> tree = map(objective.get("analysisTree"));
            List<String> objectiveMetrics = strings(objective.get("metrics"));
            for (Map<String, Object> node : maps(tree.get("children"))) {
                String question = text(node.get("question"), "");
                if (question.isBlank() || containsQuestion(target, question, reference)) continue;
                List<String> candidates = new ArrayList<>(strings(node.get("candidateDimensions")));
                candidates.addAll(objectiveMetrics);
                List<String> fields = resolveFields(candidates, reference, capabilities);
                String method = chooseMethod(methods, fields, reference, capabilities);
                target.add(subQuestion(question, method, fields, reference,
                    baseline(null, reference, capabilities)));
                if (target.size() >= MAX_SUB_QUESTIONS) return;
            }
        }
    }

    private String chooseMethod(List<String> methods, List<String> fields, String reference,
                                Map<String, Object> capabilities) {
        Map<String, Object> dataset = capabilityDataset(reference, capabilities);
        Set<String> dimensions = new LinkedHashSet<>(strings(dataset.get("decomposableDimensions")));
        Set<String> datasetMethods = new LinkedHashSet<>(strings(dataset.get("supportedMethodology")));
        if (methods.contains("DECOMPOSE") && datasetMethods.contains("DECOMPOSE")
            && fields.stream().anyMatch(dimensions::contains)) return "DECOMPOSE";
        return methods.stream().filter(datasetMethods::contains).findFirst().orElse("OBSERVE");
    }

    private boolean methodSupportedForDataset(String method, String reference,
                                              Map<String, Object> capabilities) {
        if ("CROSS_VALIDATE".equals(method)) {
            return strings(capabilities.get("supportedMethodology")).contains(method);
        }
        Map<String, Object> dataset = capabilityDataset(reference, capabilities);
        return strings(dataset.get("supportedMethodology")).contains(method);
    }

    private Map<String, Object> subQuestion(String question, String method, List<String> fields,
                                            String reference, String baseline) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("question", question);
        item.put("method", method);
        item.put("targetFields", fields);
        if (!reference.isBlank()) item.put("datasetReference", reference);
        item.put("baseline", baseline);
        return Map.copyOf(item);
    }

    private List<String> resolveFields(List<String> requested, String reference,
                                       Map<String, Object> capabilities) {
        Map<String, Object> dataset = capabilityDataset(reference, capabilities);
        Map<String, String> declared = new LinkedHashMap<>();
        for (Map<String, Object> field : maps(dataset.get("fields"))) {
            String technical = text(field.get("technicalName"), "");
            if (technical.isBlank()) continue;
            declared.put(normalize(technical), technical);
            String display = text(field.get("displayName"), "");
            if (!display.isBlank()) declared.putIfAbsent(normalize(display), technical);
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String candidate : requested) {
            String field = declared.get(normalize(candidate));
            if (field != null) resolved.add(field);
        }
        return List.copyOf(resolved);
    }

    private String baseline(Object supplied, String reference, Map<String, Object> capabilities) {
        Map<String, Object> dataset = capabilityDataset(reference, capabilities);
        List<String> declared = strings(dataset.get("declaredBaselines"));
        String requested = text(supplied, "");
        if (declared.isEmpty()) return "NONE_DECLARED";
        return declared.contains(requested) ? requested : declared.get(0);
    }

    private Map<String, Object> capabilityDataset(String reference, Map<String, Object> capabilities) {
        List<Map<String, Object>> datasets = maps(capabilities.get("datasets"));
        if (!reference.isBlank()) {
            for (Map<String, Object> dataset : datasets) {
                if (reference.equals(text(dataset.get("datasetReference"), ""))) return dataset;
            }
        }
        return datasets.size() == 1 ? datasets.get(0) : Map.of();
    }

    private String datasetReference(Map<String, Object> dataset) {
        return text(map(dataset.get("dataset")).get("datasetReference"),
            text(map(map(dataset.get("dataset")).get("dataset")).get("technicalReference"), ""));
    }

    private boolean containsQuestion(List<Map<String, Object>> values, String question, String reference) {
        return values.stream().anyMatch(value -> question.equals(value.get("question"))
            && reference.equals(text(value.get("datasetReference"), "")));
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : iterable) {
            Map<String, Object> map = map(item);
            if (!map.isEmpty()) result.add(map);
        }
        return result;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> { if (key != null && item != null) result.put(String.valueOf(key), item); });
        return result;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        iterable.forEach(item -> { if (!text(item, "").isBlank()) result.add(text(item, "")); });
        return List.copyOf(result);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    record Compiled(List<String> methodology, List<String> rejectedMethodology,
                    Map<String, Object> analysisPlan) { }
}
