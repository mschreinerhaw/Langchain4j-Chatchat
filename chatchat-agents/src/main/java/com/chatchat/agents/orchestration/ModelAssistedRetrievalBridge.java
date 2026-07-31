package com.chatchat.agents.orchestration;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Applies tool-published model-assisted retrieval contracts before tool execution.
 *
 * <p>Only declared argument paths may be changed. Runtime-owned routing, permission,
 * tenant, user and exact-target fields remain untouched unless a tool explicitly
 * publishes them as mutable.</p>
 */
@Slf4j
class ModelAssistedRetrievalBridge {

    static final String CONTRACT_VERSION = "model_assisted_retrieval.v1";
    static final String TEMPLATE_KEYWORD_EVIDENCE_VERSION = "template_retrieval_keyword_evidence.v1";
    static final String META_KEY = "modelInputBridgeContract";
    static final String RUNTIME_GATE_KEY = "__modelRetrievalQualityGate";
    private static final int MAX_CONTEXT_CHARS = 16_000;

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final EnterpriseMetadataSearchBridge enterpriseMetadataBridge;

    ModelAssistedRetrievalBridge(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.enterpriseMetadataBridge = new EnterpriseMetadataSearchBridge(this.objectMapper);
    }

    Map<String, Object> enrich(ChatModel chatModel,
                               String toolName,
                               Map<String, Object> arguments) {
        return enrichWithGate(chatModel, toolName, arguments).arguments();
    }

    EnrichmentResult enrichWithGate(ChatModel chatModel,
                                    String toolName,
                                    Map<String, Object> arguments) {
        return enrichWithGate(chatModel, toolName, arguments, RetrievalEvidenceContext.empty());
    }

    EnrichmentResult enrichWithGate(ChatModel chatModel,
                                    String toolName,
                                    Map<String, Object> arguments,
                                    RetrievalEvidenceContext evidenceContext) {
        Map<String, Object> original = deepMutableMap(arguments);
        Map<String, Object> contract = contract(toolName);
        if (chatModel == null || contract.isEmpty()
            || !CONTRACT_VERSION.equals(text(contract.get("contractVersion")))) {
            return new EnrichmentResult(original, Map.of(), false);
        }
        String mode = text(contract.get("mode"));
        if ("ENTERPRISE_METADATA_PROFILE".equalsIgnoreCase(mode)) {
            Map<String, Object> enriched = enterpriseMetadataBridge.enrich(chatModel, toolName, original);
            return resultWithGate(original, enriched, contract);
        }
        List<String> contextPaths = strings(contract.get("contextPaths"));
        List<String> allowedPaths = strings(contract.get("allowedArgumentPaths"));
        if (contextPaths.isEmpty() || allowedPaths.isEmpty()) {
            return new EnrichmentResult(original, Map.of(), false);
        }
        Map<String, Object> context = new LinkedHashMap<>();
        for (String path : contextPaths) {
            Object value = valueAtPath(original, path);
            if (meaningful(value)) {
                context.put(path, value);
            }
        }
        if ("BILINGUAL_TEMPLATE_PROFILE".equalsIgnoreCase(mode)) {
            addTrustedTemplateEvidenceContext(context, evidenceContext);
        }
        if (context.isEmpty()) {
            return new EnrichmentResult(original, Map.of(), false);
        }
        try {
            String prompt = buildPrompt(toolName, contract, context, allowedPaths);
            Map<String, Object> response = parseObject(chatModel.chat(prompt));
            Map<String, Object> patch = map(response.get("arguments"));
            if (patch.isEmpty()) {
                return new EnrichmentResult(original, Map.of(), false);
            }
            KeywordEvidenceReview keywordReview = "BILINGUAL_TEMPLATE_PROFILE".equalsIgnoreCase(mode)
                ? reviewTemplateKeywordEvidence(patch, response.get("argumentEvidence"), context, allowedPaths)
                : KeywordEvidenceReview.notRequired(patch);
            patch = keywordReview.patch();
            if (patch.isEmpty()) {
                log.warn("Model-assisted template retrieval discarded every proposed keyword because no "
                        + "verifiable source-path evidence was supplied tool={}",
                    toolName);
                return new EnrichmentResult(original, Map.of(), false);
            }
            Map<String, Object> enriched = deepMutableMap(original);
            Map<String, Object> mergeModes = map(contract.get("mergeModes"));
            int changed = 0;
            for (String path : allowedPaths) {
                Object proposed = valueAtPath(patch, path);
                if (!meaningful(proposed)) {
                    continue;
                }
                Object current = valueAtPath(original, path);
                String mergeMode = text(mergeModes.get(path));
                Object merged = mergeValue(current, proposed, mergeMode);
                if (meaningful(merged) && !String.valueOf(merged).equals(String.valueOf(current))) {
                    putAtPath(enriched, path, merged);
                    changed++;
                }
            }
            if (changed > 0) {
                log.info("Model-assisted retrieval bridge enriched tool={} mode={} changedPaths={} "
                        + "allowedPaths={} verifiedKeywordCount={} rejectedKeywordCount={}",
                    toolName, mode, changed, allowedPaths, keywordReview.evidence().size(),
                    keywordReview.rejectedCount());
            }
            return resultWithGate(original, enriched, contract, keywordReview.evidence());
        } catch (Exception ex) {
            log.warn("Model-assisted retrieval bridge fell back to original input tool={} mode={} reason={}",
                toolName, mode, ex.getMessage());
            return new EnrichmentResult(original, Map.of(), false);
        }
    }

    private void addTrustedTemplateEvidenceContext(Map<String, Object> context,
                                                   RetrievalEvidenceContext evidenceContext) {
        RetrievalEvidenceContext trusted = evidenceContext == null
            ? RetrievalEvidenceContext.empty() : evidenceContext;
        if (text(trusted.userQuery()) != null) {
            context.put("runtime.userQuery", trusted.userQuery());
        }
        trusted.completedStepOutputs().forEach((stepId, output) -> {
            if (stepId != null && meaningful(output)) {
                context.put("completedStep." + stepId + ".output", output);
            }
        });
    }

    private EnrichmentResult resultWithGate(Map<String, Object> original,
                                            Map<String, Object> enriched,
                                            Map<String, Object> contract) {
        return resultWithGate(original, enriched, contract, List.of());
    }

    private EnrichmentResult resultWithGate(Map<String, Object> original,
                                            Map<String, Object> enriched,
                                            Map<String, Object> contract,
                                            List<Map<String, Object>> keywordEvidence) {
        List<String> allowedPaths = strings(contract.get("allowedArgumentPaths"));
        if (allowedPaths.isEmpty()) {
            allowedPaths = List.of("query", "queryTerms", "fieldProfiles", "fields");
        }
        List<String> changedPaths = allowedPaths.stream()
            .filter(path -> !java.util.Objects.equals(valueAtPath(original, path), valueAtPath(enriched, path)))
            .toList();
        if (changedPaths.isEmpty()) {
            return new EnrichmentResult(enriched, Map.of(), false);
        }
        Map<String, Object> qualityGate = map(contract.get("qualityGate"));
        if (!Boolean.TRUE.equals(qualityGate.get("enabled"))) {
            return new EnrichmentResult(enriched, Map.of(), true);
        }
        Map<String, Object> originalValues = new LinkedHashMap<>();
        List<String> originallyAbsent = new ArrayList<>();
        for (String path : changedPaths) {
            Object value = valueAtPath(original, path);
            if (value == null) {
                originallyAbsent.add(path);
            } else {
                originalValues.put(path, value);
            }
        }
        Map<String, Object> gate = new LinkedHashMap<>(qualityGate);
        gate.put("contractVersion", CONTRACT_VERSION);
        gate.put("changedPaths", changedPaths);
        gate.put("originalValues", originalValues);
        gate.put("originallyAbsentPaths", originallyAbsent);
        if (keywordEvidence != null && !keywordEvidence.isEmpty()) {
            gate.put("keywordEvidenceVersion", TEMPLATE_KEYWORD_EVIDENCE_VERSION);
            gate.put("keywordEvidence", List.copyOf(keywordEvidence));
        }
        return new EnrichmentResult(enriched, Map.copyOf(gate), true);
    }

    private KeywordEvidenceReview reviewTemplateKeywordEvidence(Map<String, Object> patch,
                                                                Object rawEvidence,
                                                                Map<String, Object> context,
                                                                List<String> allowedPaths) {
        Map<String, Object> evidenceByPath = map(rawEvidence);
        Map<String, Object> verifiedPatch = new LinkedHashMap<>();
        List<Map<String, Object>> verifiedEvidence = new ArrayList<>();
        int proposedCount = 0;
        for (String path : allowedPaths) {
            Object proposed = valueAtPath(patch, path);
            List<String> values = strings(proposed);
            if (values.isEmpty()) {
                continue;
            }
            proposedCount += values.size();
            List<Map<String, Object>> candidates = evidenceItems(evidenceByPath.get(path));
            List<String> accepted = new ArrayList<>();
            for (String value : values) {
                Map<String, Object> evidence = candidates.stream()
                    .filter(item -> verifiedKeywordEvidence(value, item, context))
                    .findFirst()
                    .orElse(null);
                if (evidence == null) {
                    continue;
                }
                accepted.add(value);
                verifiedEvidence.add(Map.of(
                    "argumentPath", path,
                    "value", value,
                    "sourcePath", firstText(evidence, "sourcePath", "source_path", "path"),
                    "quote", firstText(evidence, "quote", "text", "excerpt")
                ));
            }
            if (accepted.isEmpty()) {
                continue;
            }
            putAtPath(verifiedPatch, path,
                proposed instanceof Iterable<?> ? List.copyOf(accepted) : accepted.get(0));
        }
        return new KeywordEvidenceReview(
            verifiedPatch,
            List.copyOf(verifiedEvidence),
            Math.max(0, proposedCount - verifiedEvidence.size())
        );
    }

    private List<Map<String, Object>> evidenceItems(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : iterable) {
                Map<String, Object> evidence = map(item);
                if (!evidence.isEmpty()) {
                    result.add(evidence);
                }
            }
            return List.copyOf(result);
        }
        Map<String, Object> evidence = map(value);
        return evidence.isEmpty() ? List.of() : List.of(evidence);
    }

    private boolean verifiedKeywordEvidence(String proposed,
                                            Map<String, Object> evidence,
                                            Map<String, Object> context) {
        String evidenceValue = firstText(evidence, "value", "keyword", "term");
        String sourcePath = firstText(evidence, "sourcePath", "source_path", "path");
        String quote = firstText(evidence, "quote", "text", "excerpt");
        if (evidenceValue == null || sourcePath == null || quote == null
            || !compact(proposed).equals(compact(evidenceValue))
            || !compact(quote).contains(compact(proposed))
            || !trustedTemplateEvidencePath(sourcePath)
            || !context.containsKey(sourcePath)) {
            return false;
        }
        return containsEvidenceQuote(context.get(sourcePath), quote);
    }

    private boolean trustedTemplateEvidencePath(String sourcePath) {
        return "runtime.userQuery".equals(sourcePath)
            || (sourcePath.startsWith("completedStep.") && sourcePath.endsWith(".output"));
    }

    private boolean containsEvidenceQuote(Object source, String quote) {
        if (source instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(value -> containsEvidenceQuote(value, quote));
        }
        if (source instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsEvidenceQuote(item, quote)) {
                    return true;
                }
            }
            return false;
        }
        String sourceText = text(source);
        return sourceText != null && compact(sourceText).contains(compact(quote));
    }

    private Map<String, Object> contract(String toolName) {
        if (toolRegistry == null || toolName == null) {
            return Map.of();
        }
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        if (metadata == null || metadata.getMetadata() == null) {
            return Map.of();
        }
        Map<String, Object> metadataMap = map(metadata.getMetadata());
        Map<String, Object> mcpMeta = map(metadataMap.get("mcpToolMeta"));
        Map<String, Object> contract = map(mcpMeta.get(META_KEY));
        if (!contract.isEmpty()) {
            return contract;
        }
        return map(metadataMap.get(META_KEY));
    }

    private String buildPrompt(String toolName,
                               Map<String, Object> contract,
                               Map<String, Object> context,
                               List<String> allowedPaths) throws Exception {
        String serializedContext = objectMapper.writeValueAsString(context);
        if (serializedContext.length() > MAX_CONTEXT_CHARS) {
            serializedContext = serializedContext.substring(0, MAX_CONTEXT_CHARS);
        }
        String evidenceRules = "BILINGUAL_TEMPLATE_PROFILE".equalsIgnoreCase(text(contract.get("mode")))
            ? """

            Template keyword evidence protocol:
            - Every value under arguments must have a matching entry under argumentEvidence.
            - Shape:
              "argumentEvidence":{
                "argumentPath":[{"value":"exact proposed value","sourcePath":"one Context path","quote":"exact source excerpt"}]
              }
            - sourcePath must be runtime.userQuery or completedStep.<id>.output.
            - quote must occur verbatim in that Context value, and value must occur in quote.
            - Do not translate, add synonyms, generalize or infer a keyword unless that exact value occurs
              in the cited Context excerpt. Omit unsupported keywords.
            """
            : "";
        return """
            Build a precise retrieval profile for the declared tool. Return JSON only:
            {"profile":{"intent":"short retrieval intent","terms":["search terms"]},
             "arguments":{...only the allowed argument paths...},
             "argumentEvidence":{...evidence required by the mode...}}

            Tool: %s
            Mode: %s
            Allowed argument paths: %s
            Guidance: %s

            Rules:
            1. Improve retrieval recall and precision; do not answer the user's question.
            2. Preserve exact names, quoted text, codes, versions, dates and identifiers from the input.
            3. Do not create tenant, user, role, permission, datasource, environment, endpoint,
               physical table, template id or execution fields.
            4. Output only allowed argument paths. Omit a path when no safe improvement is available.
            5. Added terms are retrieval hints, not facts or evidence.
            %s

            Context:
            %s
            """.formatted(
                toolName,
                text(contract.get("mode")),
                objectMapper.writeValueAsString(allowedPaths),
                text(contract.get("guidance")),
                evidenceRules,
                serializedContext
            );
    }

    private Object mergeValue(Object current, Object proposed, String mergeMode) {
        String mode = mergeMode == null ? "replace" : mergeMode.toLowerCase(Locale.ROOT);
        if ("append_text".equals(mode)) {
            String original = text(current);
            String addition = text(proposed);
            if (addition == null) {
                return current;
            }
            if (original == null || addition.contains(original)) {
                return addition;
            }
            return original + " " + addition;
        }
        if ("merge_array".equals(mode)) {
            Set<String> merged = new LinkedHashSet<>(strings(current));
            merged.addAll(strings(proposed));
            return List.copyOf(merged);
        }
        return proposed;
    }

    private Map<String, Object> parseObject(String raw) throws Exception {
        String json = text(raw);
        if (json == null) {
            return Map.of();
        }
        if (json.startsWith("```")) {
            int firstLine = json.indexOf('\n');
            int closing = json.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                json = json.substring(firstLine + 1, closing).trim();
            }
        }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("model response did not contain a JSON object");
        }
        return objectMapper.readValue(
            json.substring(start, end + 1),
            new TypeReference<Map<String, Object>>() { });
    }

    private Object valueAtPath(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private void putAtPath(Map<String, Object> root, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = root;
        for (int index = 0; index < segments.length - 1; index++) {
            Object nested = current.get(segments[index]);
            Map<String, Object> next;
            if (nested instanceof Map<?, ?> map) {
                next = deepMutableMap((Map<String, Object>) map);
            } else {
                next = new LinkedHashMap<>();
            }
            current.put(segments[index], next);
            current = next;
        }
        current.put(segments[segments.length - 1], value);
    }

    private boolean meaningful(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof Iterable<?> iterable) {
            return iterable.iterator().hasNext();
        }
        return true;
    }

    private List<String> strings(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            for (Object item : iterable) {
                String text = text(item);
                if (text != null) {
                    result.add(text);
                }
            }
            return List.copyOf(result);
        }
        String text = text(value);
        return text == null ? List.of() : List.of(text);
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isBlank() ? null : result;
    }

    private String firstText(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            String result = text(value.get(key));
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMutableMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                value = deepMutableMap((Map<String, Object>) map);
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    record EnrichmentResult(
        Map<String, Object> arguments,
        Map<String, Object> qualityGate,
        boolean changed
    ) {
        Map<String, Object> argumentsWithGateMarker() {
            Map<String, Object> result = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
            if (qualityGate != null && !qualityGate.isEmpty()) {
                result.put(RUNTIME_GATE_KEY, qualityGate);
            }
            return result;
        }
    }

    record RetrievalEvidenceContext(
        String userQuery,
        Map<Integer, Object> completedStepOutputs
    ) {
        RetrievalEvidenceContext {
            completedStepOutputs = completedStepOutputs == null
                ? Map.of() : Map.copyOf(completedStepOutputs);
        }

        private static RetrievalEvidenceContext empty() {
            return new RetrievalEvidenceContext(null, Map.of());
        }
    }

    private record KeywordEvidenceReview(
        Map<String, Object> patch,
        List<Map<String, Object>> evidence,
        int rejectedCount
    ) {
        private static KeywordEvidenceReview notRequired(Map<String, Object> patch) {
            return new KeywordEvidenceReview(patch, List.of(), 0);
        }
    }
}
