package com.chatchat.agents.runtime.toolcall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recovers missing direct-tool arguments from Runtime-verifiable workflow evidence.
 *
 * <p>The model may propose an evidence pointer, but it never supplies trusted data:
 * completed-step values are re-read at the declared path and user-query candidates
 * must be present in an exact quote. When no proposal exists, published field names
 * and aliases are matched deterministically against successful structured outputs.</p>
 */
public final class ContextualToolArgumentResolver {

    public static final String MODEL_EVIDENCE_FIELD = "contextParameterEvidence";

    public Resolution resolve(Request request) {
        Map<String, Object> arguments = new LinkedHashMap<>(
            request == null || request.arguments() == null ? Map.of() : request.arguments());
        Map<String, Object> schema = request == null || request.schema() == null
            ? Map.of() : request.schema();
        if (!(schema.get("properties") instanceof Map<?, ?> rawProperties)) {
            arguments.remove(MODEL_EVIDENCE_FIELD);
            return new Resolution(Map.copyOf(arguments), List.of(), List.of());
        }
        Map<String, Object> properties = stringMap(rawProperties);
        Set<String> required = new LinkedHashSet<>(stringList(schema.get("required")));
        List<EvidenceValue> flattened = flattenCompletedOutputs(request.completedStepOutputs());
        List<Map<String, Object>> proposals = evidenceProposals(arguments.remove(MODEL_EVIDENCE_FIELD));
        List<RecoveredArgument> recovered = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        for (String field : required) {
            if (hasValue(arguments.get(field))) {
                continue;
            }
            Map<String, Object> property = stringMap(properties.get(field));
            VerifiedCandidate candidate = verifiedProposal(
                field, property, proposals, request, flattened);
            if (candidate == null) {
                candidate = deterministicCandidate(field, property, flattened);
            }
            if (candidate == null) {
                unresolved.add(field);
                continue;
            }
            arguments.put(field, candidate.value());
            recovered.add(new RecoveredArgument(
                field, candidate.sourceType(), candidate.stepId(), candidate.outputPath(),
                candidate.modelProposed()));
        }
        return new Resolution(Map.copyOf(arguments), List.copyOf(recovered), List.copyOf(unresolved));
    }

    private VerifiedCandidate verifiedProposal(String field,
                                                Map<String, Object> property,
                                                List<Map<String, Object>> proposals,
                                                Request request,
                                                List<EvidenceValue> flattened) {
        for (Map<String, Object> proposal : proposals) {
            if (!matchesField(field, property, text(proposal.get("parameter")))) {
                continue;
            }
            String source = text(proposal.get("source"));
            if ("completed_step".equalsIgnoreCase(source)) {
                Integer stepId = integer(proposal.get("stepId"));
                String path = text(proposal.get("outputPath"));
                Object output = stepId == null || request.completedStepOutputs() == null
                    ? null : request.completedStepOutputs().get(stepId);
                Object verified = valueAtPath(output, path);
                if (hasValue(verified)) {
                    return new VerifiedCandidate(verified, "completed_step", stepId, path, true);
                }
            }
            if ("user_query".equalsIgnoreCase(source)) {
                String quote = text(proposal.get("quote"));
                Object value = proposal.get("value");
                String query = request.originalUserQuery();
                if (hasValue(value) && quote != null && query != null
                    && query.contains(quote) && quote.contains(String.valueOf(value))) {
                    return new VerifiedCandidate(value, "user_query", null, null, true);
                }
            }
        }
        return null;
    }

    private VerifiedCandidate deterministicCandidate(String field,
                                                      Map<String, Object> property,
                                                      List<EvidenceValue> flattened) {
        List<EvidenceValue> matches = flattened.stream()
            .filter(candidate -> matchesField(field, property, candidate.field()))
            .filter(candidate -> hasValue(candidate.value()))
            .toList();
        if (matches.isEmpty()) {
            return null;
        }
        Map<String, EvidenceValue> distinct = new LinkedHashMap<>();
        matches.forEach(candidate -> distinct.putIfAbsent(String.valueOf(candidate.value()), candidate));
        if (distinct.size() != 1) {
            return null;
        }
        EvidenceValue match = distinct.values().iterator().next();
        return new VerifiedCandidate(
            match.value(), "completed_step", match.stepId(), match.path(), false);
    }

    private boolean matchesField(String field, Map<String, Object> property, String candidate) {
        if (candidate == null) {
            return false;
        }
        Set<String> names = new LinkedHashSet<>();
        names.add(canonical(field));
        stringList(property.get("aliases")).stream().map(this::canonical).forEach(names::add);
        stringList(property.get("acceptedSources")).stream().map(this::canonical).forEach(names::add);
        return names.contains(canonical(candidate));
    }

    private List<EvidenceValue> flattenCompletedOutputs(Map<Integer, Object> outputs) {
        List<EvidenceValue> values = new ArrayList<>();
        if (outputs != null) {
            outputs.forEach((stepId, output) -> flatten(stepId, "$", null, output, values, 0));
        }
        return values;
    }

    private void flatten(Integer stepId,
                         String path,
                         String field,
                         Object value,
                         List<EvidenceValue> target,
                         int depth) {
        if (value == null || depth > 12) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                if (key != null) {
                    String name = String.valueOf(key);
                    flatten(stepId, path + "." + name, name, nested, target, depth + 1);
                }
            });
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                flatten(stepId, path + "[" + index + "]", field, list.get(index), target, depth + 1);
            }
            return;
        }
        if (field != null) {
            target.add(new EvidenceValue(stepId, path, field, value));
        }
    }

    private Object valueAtPath(Object root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = root;
        String normalized = path.trim().replaceFirst("^\\$\\.?", "");
        for (String token : normalized.split("\\.")) {
            if (token.isBlank()) continue;
            int bracket = token.indexOf('[');
            String key = bracket < 0 ? token : token.substring(0, bracket);
            if (!key.isBlank()) {
                if (!(current instanceof Map<?, ?> map)) return null;
                current = map.get(key);
            }
            while (bracket >= 0) {
                int end = token.indexOf(']', bracket);
                if (end < 0 || !(current instanceof List<?> list)) return null;
                Integer index = integer(token.substring(bracket + 1, end));
                if (index == null || index < 0 || index >= list.size()) return null;
                current = list.get(index);
                bracket = token.indexOf('[', end + 1);
            }
        }
        return current;
    }

    private List<Map<String, Object>> evidenceProposals(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(Map.class::cast)
            .map(this::stringMap).toList();
    }

    private String canonical(String value) {
        return value == null ? "" : value.replaceAll("[_\\-\\s]", "")
            .toLowerCase(Locale.ROOT);
    }

    private boolean hasValue(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Integer integer(Object value) {
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<String> values = new ArrayList<>();
        iterable.forEach(item -> {
            if (item != null && !String.valueOf(item).isBlank()) values.add(String.valueOf(item));
        });
        return values;
    }

    private Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) values.put(String.valueOf(key), item);
        });
        return values;
    }

    public record Request(Map<String, Object> arguments,
                          Map<String, Object> schema,
                          String originalUserQuery,
                          Map<Integer, Object> completedStepOutputs) {
    }

    public record Resolution(Map<String, Object> arguments,
                             List<RecoveredArgument> recovered,
                             List<String> unresolvedRequiredFields) {
        public boolean applied() {
            return recovered != null && !recovered.isEmpty();
        }
    }

    public record RecoveredArgument(String field,
                                    String sourceType,
                                    Integer stepId,
                                    String outputPath,
                                    boolean modelProposed) {
    }

    private record EvidenceValue(Integer stepId, String path, String field, Object value) {
    }

    private record VerifiedCandidate(Object value,
                                     String sourceType,
                                     Integer stepId,
                                     String outputPath,
                                     boolean modelProposed) {
    }
}
