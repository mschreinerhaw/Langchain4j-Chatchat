package com.chatchat.agents.runtime.plan.diagnostic;

import com.chatchat.agents.runtime.plan.InterpretationPlan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministically matches required diagnostic checks to producer-declared templates.
 *
 * <p>This component owns only semantic admission. It does not discover templates, execute
 * tools, or decide whether missing coverage is fatal. Keeping those concerns separate makes
 * the matching policy independently testable and prevents database-specific rules from leaking
 * into the plan scheduler.</p>
 */
public final class DiagnosticTemplateMatcher {

    public Map<Integer, Integer> assignments(
        List<InterpretationPlan.DiagnosticCheck> checks,
        List<Map<String, Object>> templates,
        Map<String, String> templateHints,
        Map<String, String> callContexts
    ) {
        List<InterpretationPlan.DiagnosticCheck> safeChecks = checks == null ? List.of() : checks;
        List<Map<String, Object>> safeTemplates = templates == null ? List.of() : templates;
        Map<String, String> safeHints = templateHints == null ? Map.of() : templateHints;
        Map<String, String> safeContexts = callContexts == null ? Map.of() : callContexts;
        List<TemplateMatch> candidates = new ArrayList<>();
        for (int checkIndex = 0; checkIndex < safeChecks.size(); checkIndex++) {
            InterpretationPlan.DiagnosticCheck check = safeChecks.get(checkIndex);
            String templateHint = safeHints.get(check.checkId());
            String callContext = safeContexts.getOrDefault(check.checkId(), "");
            for (int templateIndex = 0; templateIndex < safeTemplates.size(); templateIndex++) {
                Map<String, Object> template = safeTemplates.get(templateIndex);
                String candidateId = canonicalTemplateId(template);
                boolean exactHint = templateHint != null && candidateId != null
                    && templateHint.equalsIgnoreCase(candidateId);
                int semanticScore = semanticScore(check, template, callContext);
                int score = exactHint && semanticScore > 0
                    ? 1_000_000 + semanticScore
                    : semanticScore;
                if (score > 0) {
                    candidates.add(new TemplateMatch(checkIndex, templateIndex, score));
                }
            }
        }
        candidates.sort(java.util.Comparator
            .comparingInt(TemplateMatch::score).reversed()
            .thenComparingInt(TemplateMatch::checkIndex)
            .thenComparingInt(TemplateMatch::templateIndex));
        Set<Integer> assignedChecks = new LinkedHashSet<>();
        Set<Integer> assignedTemplates = new LinkedHashSet<>();
        Map<Integer, Integer> assignments = new LinkedHashMap<>();
        for (TemplateMatch candidate : candidates) {
            if (assignedChecks.contains(candidate.checkIndex())
                || assignedTemplates.contains(candidate.templateIndex())) {
                continue;
            }
            assignedChecks.add(candidate.checkIndex());
            assignedTemplates.add(candidate.templateIndex());
            assignments.put(candidate.checkIndex(), candidate.templateIndex());
        }
        return assignments;
    }

    public Map<String, String> resolvedTemplateHints(
        Map<String, Object> plannedInput,
        Map<String, Object> resolvedInput
    ) {
        Map<String, String> hints = new LinkedHashMap<>(templateHints(plannedInput));
        templateHints(resolvedInput).forEach(hints::put);
        return Map.copyOf(hints);
    }

    public Map<String, String> resolvedCallContexts(
        Map<String, Object> plannedInput,
        Map<String, Object> resolvedInput
    ) {
        Map<String, String> contexts = new LinkedHashMap<>(callContexts(plannedInput));
        callContexts(resolvedInput).forEach(contexts::put);
        return Map.copyOf(contexts);
    }

    private Map<String, String> templateHints(Map<String, Object> stepInput) {
        if (stepInput == null || !(stepInput.get("calls") instanceof Iterable<?> calls)) {
            return Map.of();
        }
        Map<String, String> hints = new LinkedHashMap<>();
        for (Object item : calls) {
            if (!(item instanceof Map<?, ?> call)) continue;
            String callId = text(first(call, "callId", "call_id", "checkId", "check_id"));
            Object rawArguments = first(call, "arguments", "input");
            Map<?, ?> arguments = rawArguments instanceof Map<?, ?> map ? map : Map.of();
            String templateId = canonicalTemplateId(firstNonBlank(
                text(first(arguments, "templateId", "template_id", "template")),
                text(first(call, "templateId", "template_id", "template"))));
            if (callId != null && templateId != null) hints.putIfAbsent(callId, templateId);
        }
        return Map.copyOf(hints);
    }

    private Map<String, String> callContexts(Map<String, Object> stepInput) {
        if (stepInput == null || !(stepInput.get("calls") instanceof Iterable<?> calls)) {
            return Map.of();
        }
        Map<String, String> contexts = new LinkedHashMap<>();
        for (Object item : calls) {
            if (!(item instanceof Map<?, ?> call)) continue;
            String callId = text(first(call, "callId", "call_id", "checkId", "check_id"));
            if (callId == null) continue;
            List<Object> values = new ArrayList<>();
            values.add(callId);
            for (String key : List.of(
                "purpose", "description", "reason", "requiredMetrics", "required_metrics",
                "requiredFields", "required_fields", "healthCapability", "health_capability"
            )) {
                Object value = call.get(key);
                if (value != null) values.add(value);
            }
            Object rawArguments = first(call, "arguments", "input");
            if (rawArguments instanceof Map<?, ?> arguments) {
                for (String key : List.of("purpose", "description", "reason")) {
                    Object value = arguments.get(key);
                    if (value != null) values.add(value);
                }
            }
            contexts.put(callId, values.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(" ")));
        }
        return Map.copyOf(contexts);
    }

    private int semanticScore(InterpretationPlan.DiagnosticCheck check,
                              Map<String, Object> template,
                              String callContext) {
        if (check == null || template == null || template.isEmpty()) {
            return 0;
        }
        String canonicalId = firstNonBlank(canonicalTemplateId(template), "");
        String fullIdentity = templateIdentity(template);
        Set<String> checkTokens = tokens(
            firstNonBlank(check.checkId(), "") + " " + firstNonBlank(check.capability(), "")
                + " " + firstNonBlank(check.dimension(), "") + " "
                + firstNonBlank(callContext, ""));
        Set<String> canonicalTokens = tokens(canonicalId);
        Set<String> identityTokens = tokens(fullIdentity);
        int canonicalMatches = 0;
        int totalMatches = 0;
        int score = 0;
        for (String token : checkTokens) {
            if (canonicalTokens.contains(token)) {
                canonicalMatches++;
                totalMatches++;
                score += token.length() * 20;
            } else if (identityTokens.contains(token)) {
                totalMatches++;
                score += token.length();
            }
        }
        String normalizedIdentity = normalizePhrase(fullIdentity);
        boolean exactSemanticPhrase = Arrays.asList(
                check.checkId(), check.capability(), check.dimension())
            .stream()
            .map(this::normalizePhrase)
            .filter(phrase -> phrase.length() >= 2)
            .anyMatch(normalizedIdentity::contains);
        if (exactSemanticPhrase) {
            score += 100;
        }
        if (canonicalMatches > 0 || totalMatches >= 2 || exactSemanticPhrase) {
            return score;
        }
        String normalizedCheckId = normalizePhrase(check.checkId());
        boolean broadCapability = !normalizedCheckId.isBlank()
            && normalizedCheckId.equals(normalizePhrase(check.capability()));
        return broadCapability && totalMatches == 1 ? score : 0;
    }

    private String templateIdentity(Map<String, Object> template) {
        List<Object> values = Arrays.asList(
            canonicalTemplateId(template), template.get("name"), template.get("displayName"),
            template.get("capability"), template.get("diagnosticCapability"), template.get("purpose"),
            template.get("diagnosticPurpose"), template.get("description"), template.get("category"),
            template.get("operationType"), template.get("keywords"), template.get("tags")
        );
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .filter(value -> !value.isBlank())
            .collect(Collectors.joining(" "));
    }

    private Set<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> ignored = Set.of(
            "database", "query", "execute", "template", "diagnostic", "check"
        );
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : value.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}a-z0-9]+")) {
            String token = raw.endsWith("s") && raw.length() > 4
                ? raw.substring(0, raw.length() - 1)
                : raw;
            boolean han = token.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
            if ((han && token.length() >= 2 || !han && token.length() >= 4)
                && !ignored.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String normalizePhrase(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
            .trim();
    }

    private String canonicalTemplateId(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map) {
            Object nested = first(map, "templateId", "template_id", "id", "code", "template");
            if (nested == null) {
                nested = nestedValue(map, "execution", "templateId", "template");
            }
            if (nested == null) {
                nested = nestedValue(map, "executionBinding", "templateId");
            }
            if (nested == null) {
                nested = nestedValue(map, "sqlExecutionBinding", "templateId");
            }
            return nested == value ? null : canonicalTemplateId(nested);
        }
        if (!(value instanceof CharSequence) || value.toString().isBlank()) return null;
        String text = value.toString().trim();
        return text.startsWith("{{") ? null : text;
    }

    private Object nestedValue(Map<?, ?> map, String key, String... nestedKeys) {
        Object value = map.get(key);
        return value instanceof Map<?, ?> nested ? first(nested, nestedKeys) : null;
    }

    private Object first(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) return null;
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) return value;
        }
        return null;
    }

    private String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private record TemplateMatch(int checkIndex, int templateIndex, int score) {
    }
}
