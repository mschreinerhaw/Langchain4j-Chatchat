package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.model.AgentBudgetExceededException;
import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.agents.runtime.batch.ToolCallBatchSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Schema-driven Function Calling adapter for template parameters.
 *
 * <p>The model only proposes values for fields published by the selected template schemas.
 * Runtime converts those proposals to the evidence-bearing parameter protocol and remains the
 * sole validation/execution boundary. Repeated bindings for one template are intentional: they
 * represent collection-valued user intent without teaching the OS any business entity names.</p>
 */
@Slf4j
final class SchemaDrivenTemplateParameterBinder {

    static final String FUNCTION_NAME = "bind_template_parameters";

    private final ObjectMapper objectMapper;

    SchemaDrivenTemplateParameterBinder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    Optional<BindingResult> bind(ChatModel model,
                                 String userQuery,
                                 List<Map<String, Object>> returnedTemplates,
                                 List<String> selectedTemplateIds) {
        if (model == null || selectedTemplateIds == null || selectedTemplateIds.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Map<String, Object>> selected = selectedTemplates(returnedTemplates,
            selectedTemplateIds);
        if (selected.size() != new LinkedHashSet<>(selectedTemplateIds).size()) {
            return Optional.empty();
        }
        try {
            ToolSpecification specification = specification(selected);
            List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt()),
                UserMessage.from(userPrompt(userQuery, selected)));
            ChatResponse response;
            try {
                response = nativeCall(model, messages, specification, ToolChoice.REQUIRED);
            } catch (AgentBudgetExceededException | CancellationException failure) {
                throw failure;
            } catch (Exception requiredChoiceUnsupported) {
                // Some thinking-mode providers support tools but explicitly reject REQUIRED.
                // AUTO is a transport compatibility retry; acceptance below still requires the
                // one Runtime-designated function and never parses free-form text as arguments.
                log.info("Template parameter Function Calling retrying with AUTO tool choice: {}",
                    requiredChoiceUnsupported.getMessage());
                response = nativeCall(model, messages, specification, ToolChoice.AUTO);
            }
            AiMessage message = response == null ? null : response.aiMessage();
            if (message == null || !message.hasToolExecutionRequests()) return Optional.empty();
            List<ToolExecutionRequest> requests = message.toolExecutionRequests();
            if (requests == null || requests.size() != 1
                || !FUNCTION_NAME.equals(requests.get(0).name())) {
                return Optional.empty();
            }
            Map<String, Object> envelope = objectMapper.readValue(
                requests.get(0).arguments(), new TypeReference<>() {});
            List<Map<String, Object>> protocols = protocols(
                envelope.get("bindings"), userQuery, selected);
            Set<String> covered = protocols.stream()
                .map(protocol -> text(protocol.get("template_id")))
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!covered.equals(selected.keySet())) return Optional.empty();
            int verified = protocols.stream().mapToInt(protocol -> map(protocol.get("arguments")).size()).sum();
            int unresolved = protocols.stream()
                .map(protocol -> protocol.get("unresolved_parameters"))
                .filter(Collection.class::isInstance)
                .map(Collection.class::cast)
                .mapToInt(Collection::size)
                .sum();
            int proposed = verified + unresolved;
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("bindingCount", protocols.size());
            metrics.put("selectedTemplateCount", selected.size());
            metrics.put("coveredTemplateCount", covered.size());
            metrics.put("templateCoverageRate", selected.isEmpty() ? 0.0
                : (double) covered.size() / selected.size());
            metrics.put("proposedParameterCount", proposed);
            metrics.put("evidenceVerifiedParameterCount", verified);
            metrics.put("unresolvedParameterCount", unresolved);
            metrics.put("evidenceVerificationRate", proposed == 0 ? 1.0
                : (double) verified / proposed);
            metrics.put("executionCardinalityLimit", ToolCallBatchSchema.DEFAULT_MAX_CALLS);
            return Optional.of(new BindingResult(
                List.copyOf(protocols), requests.get(0).id(), Map.copyOf(metrics),
                "native_function_calling"));
        } catch (AgentBudgetExceededException | CancellationException failure) {
            throw failure;
        } catch (Exception failure) {
            log.info("Schema-driven template parameter Function Calling unavailable; preserving reviewer protocol: {}",
                failure.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Completes a reviewed entity-by-template matrix after a coverage audit adds templates.
     * Field compatibility comes exclusively from the published schemas: no entity or business
     * parameter name is known to the Runtime. Existing evidence envelopes are preserved.
     */
    Optional<BindingResult> completeFromReviewedProtocols(
        List<Map<String, Object>> returnedTemplates,
        List<String> selectedTemplateIds,
        Object reviewedProtocols
    ) {
        Map<String, Map<String, Object>> selected = selectedTemplates(
            returnedTemplates, selectedTemplateIds);
        if (selected.size() != new LinkedHashSet<>(selectedTemplateIds).size()
            || !(reviewedProtocols instanceof Collection<?> source)) {
            return Optional.empty();
        }
        List<Map<String, Object>> sourceProtocols = source.stream()
            .filter(Map.class::isInstance)
            .map(item -> map(item))
            .toList();
        if (sourceProtocols.isEmpty()) return Optional.empty();

        List<Map<String, Object>> completed = new ArrayList<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        int ordinal = 0;
        for (Map.Entry<String, Map<String, Object>> selectedEntry : selected.entrySet()) {
            String targetId = templateId(selectedEntry.getValue());
            Map<String, Object> schema = parameterSchema(selectedEntry.getValue());
            Map<String, Object> properties = map(schema.get("properties"));
            Set<String> requiredWithoutDefaults = requiredWithoutDefaults(schema, properties);
            List<Map<String, Object>> exactTemplateProtocols = sourceProtocols.stream()
                .filter(protocol -> selectedEntry.getKey().equalsIgnoreCase(
                    Objects.toString(protocol.get("template_id"), "")))
                .toList();
            boolean reusingExactTemplateProtocol = !exactTemplateProtocols.isEmpty();
            List<Map<String, Object>> candidateProtocols = reusingExactTemplateProtocol
                ? exactTemplateProtocols : sourceProtocols;
            for (Map<String, Object> sourceProtocol : candidateProtocols) {
                Map<String, Object> projected = new LinkedHashMap<>();
                map(sourceProtocol.get("arguments")).forEach((name, evidence) -> {
                    if (properties.containsKey(name) && evidence instanceof Map<?, ?>) {
                        projected.put(name, evidence);
                    }
                });
                // A cross-template projection that carries no reviewed user evidence must not
                // silently fall back to a template's example/default entity. This previously
                // caused a request for one customer to execute added templates for the customer
                // embedded in their catalog defaults. Exact-template protocols remain eligible
                // for deliberate default-only templates.
                if (!reusingExactTemplateProtocol && projected.isEmpty() && !properties.isEmpty()) {
                    continue;
                }
                if (!projected.keySet().containsAll(requiredWithoutDefaults)) continue;
                String fingerprint = selectedEntry.getKey() + "|" + projected;
                if (!fingerprints.add(fingerprint)) continue;
                if (completed.size() >= ToolCallBatchSchema.DEFAULT_MAX_CALLS) {
                    return Optional.empty();
                }
                Map<String, Object> protocol = new LinkedHashMap<>();
                protocol.put("protocol_version", AgentProtocolCatalog.TEMPLATE_PARAMETER);
                protocol.put("template_id", targetId);
                protocol.put("binding_id", "schema-completed-" + (++ordinal));
                protocol.put("arguments", Map.copyOf(projected));
                protocol.put("unresolved_parameters", List.of());
                protocol.put("generation_mode", "schema_reused_reviewed_protocol");
                completed.add(Map.copyOf(protocol));
            }
        }
        Set<String> covered = completed.stream()
            .map(protocol -> text(protocol.get("template_id")))
            .filter(Objects::nonNull)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!covered.equals(selected.keySet())) return Optional.empty();
        Map<String, Object> metrics = Map.of(
            "bindingCount", completed.size(),
            "selectedTemplateCount", selected.size(),
            "coveredTemplateCount", covered.size(),
            "templateCoverageRate", 1.0,
            "executionCardinalityLimit", ToolCallBatchSchema.DEFAULT_MAX_CALLS
        );
        return Optional.of(new BindingResult(List.copyOf(completed), null, metrics,
            "schema_reused_reviewed_protocol"));
    }

    private Set<String> requiredWithoutDefaults(Map<String, Object> schema,
                                                Map<String, Object> properties) {
        Object requiredValue = schema.get("required");
        if (!(requiredValue instanceof Collection<?> required)) return Set.of();
        return required.stream()
            .map(this::text)
            .filter(Objects::nonNull)
            .filter(name -> !map(properties.get(name)).containsKey("default"))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private ChatResponse nativeCall(ChatModel model,
                                    List<dev.langchain4j.data.message.ChatMessage> messages,
                                    ToolSpecification specification,
                                    ToolChoice choice) {
        return model.chat(ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(List.of(specification))
            .toolChoice(choice)
            .build());
    }

    private ToolSpecification specification(Map<String, Map<String, Object>> selected) throws Exception {
        List<Map<String, Object>> variants = new ArrayList<>();
        selected.forEach((normalizedId, template) -> {
            String templateId = templateId(template);
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("type", "object");
            variant.put("additionalProperties", false);
            variant.put("required", List.of("template_id", "arguments"));
            variant.put("properties", Map.of(
                "template_id", Map.of("type", "string", "enum", List.of(templateId)),
                "binding_id", Map.of("type", "string", "maxLength", 128),
                "arguments", bindingParameterSchema(template)
            ));
            variants.add(variant);
        });
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.put("required", List.of("bindings"));
        parameters.put("properties", Map.of("bindings", Map.of(
            "type", "array",
            "minItems", selected.size(),
            "maxItems", ToolCallBatchSchema.DEFAULT_MAX_CALLS,
            "items", Map.of("oneOf", variants)
        )));
        ObjectNode specification = objectMapper.createObjectNode();
        specification.put("name", FUNCTION_NAME);
        specification.put("description",
            "Bind explicit user values to selected template schemas. Return one binding per distinct entity/template combination.");
        specification.set("parameters", objectMapper.valueToTree(parameters));
        return ToolSpecification.fromJson(objectMapper.writeValueAsString(specification));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> protocols(Object value,
                                                String userQuery,
                                                Map<String, Map<String, Object>> selected) {
        if (!(value instanceof Collection<?> bindings)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        int ordinal = 0;
        for (Object item : bindings) {
            if (!(item instanceof Map<?, ?> raw) || result.size() >= ToolCallBatchSchema.DEFAULT_MAX_CALLS) {
                continue;
            }
            Map<String, Object> binding = new LinkedHashMap<>((Map<String, Object>) raw);
            String requestedId = text(binding.get("template_id"));
            Map<String, Object> template = requestedId == null
                ? null : selected.get(requestedId.toLowerCase(Locale.ROOT));
            if (template == null) continue;
            String authoritativeId = templateId(template);
            Map<String, Object> schemaProperties = map(parameterSchema(template).get("properties"));
            Map<String, Object> proposed = map(binding.get("arguments"));
            Map<String, Object> audited = new LinkedHashMap<>();
            List<String> unresolved = new ArrayList<>();
            for (Map.Entry<String, Object> argument : proposed.entrySet()) {
                if (!schemaProperties.containsKey(argument.getKey()) || !hasValue(argument.getValue())) continue;
                String quote = exactEvidenceQuote(userQuery, argument.getValue());
                if (quote == null) {
                    unresolved.add(argument.getKey());
                    continue;
                }
                audited.put(argument.getKey(), Map.of(
                    "value", argument.getValue(),
                    "source", "user_query",
                    "evidence", Map.of("quote", quote, "generated_by", "native_function_calling")
                ));
            }
            String fingerprint = authoritativeId.toLowerCase(Locale.ROOT) + "|" + audited;
            if (!fingerprints.add(fingerprint)) continue;
            String bindingId = text(binding.get("binding_id"));
            if (bindingId == null) bindingId = "binding-" + (++ordinal);
            Map<String, Object> protocol = new LinkedHashMap<>();
            protocol.put("protocol_version", AgentProtocolCatalog.TEMPLATE_PARAMETER);
            protocol.put("template_id", authoritativeId);
            protocol.put("binding_id", bindingId);
            protocol.put("arguments", Map.copyOf(audited));
            protocol.put("unresolved_parameters", List.copyOf(unresolved));
            protocol.put("generation_mode", "native_function_calling");
            result.add(Map.copyOf(protocol));
        }
        return result;
    }

    private Map<String, Map<String, Object>> selectedTemplates(List<Map<String, Object>> templates,
                                                                List<String> selectedIds) {
        Set<String> requested = selectedIds.stream().filter(Objects::nonNull)
            .map(id -> id.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (templates != null) {
            for (Map<String, Object> template : templates) {
                String id = templateId(template);
                if (id != null && requested.contains(id.toLowerCase(Locale.ROOT))) {
                    result.putIfAbsent(id.toLowerCase(Locale.ROOT), template);
                }
            }
        }
        return result;
    }

    private Map<String, Object> parameterSchema(Map<String, Object> template) {
        Map<String, Object> schema = map(first(template,
            "parameterSchema", "parameter_schema", "inputSchema", "input_schema"));
        if (schema.isEmpty()) return Map.of(
            "type", "object", "properties", Map.of(), "additionalProperties", false);
        Map<String, Object> copy = new LinkedHashMap<>(schema);
        copy.putIfAbsent("type", "object");
        copy.putIfAbsent("properties", Map.of());
        copy.put("additionalProperties", false);
        return Map.copyOf(copy);
    }

    /** Defaults remain Runtime-owned, so Function Calling only requires fields that have no
     * published default. This prevents the model from copying or rewriting authoritative values. */
    private Map<String, Object> bindingParameterSchema(Map<String, Object> template) {
        Map<String, Object> schema = new LinkedHashMap<>(parameterSchema(template));
        Map<String, Object> properties = map(schema.get("properties"));
        Object requiredValue = schema.get("required");
        if (requiredValue instanceof Collection<?> required) {
            List<String> withoutDefaults = required.stream()
                .map(this::text)
                .filter(Objects::nonNull)
                .filter(name -> !map(properties.get(name)).containsKey("default"))
                .toList();
            if (withoutDefaults.isEmpty()) schema.remove("required");
            else schema.put("required", withoutDefaults);
        }
        return Map.copyOf(schema);
    }

    private String systemPrompt() {
        return "You bind user language to Runtime-published JSON Schemas. The template and field names are opaque. "
            + "Use only schema descriptions and literal user evidence. Never invent identifiers, dates, defaults or fields. "
            + "For a collection of entities, emit one binding for every entity and every selected template. "
            + "Omit default-backed fields unless the user explicitly overrides them.";
    }

    private String userPrompt(String query, Map<String, Map<String, Object>> selected) throws Exception {
        List<Map<String, Object>> contracts = selected.values().stream().map(template -> Map.of(
            "template_id", templateId(template),
            "parameter_schema", parameterSchema(template)
        )).toList();
        return "Current-turn user query:\n" + (query == null ? "" : query)
            + "\n\nSelected template parameter contracts:\n"
            + objectMapper.writeValueAsString(contracts);
    }

    private String exactEvidenceQuote(String query, Object value) {
        if (query == null || value == null || value instanceof Map<?, ?>
            || value instanceof Collection<?> || value.getClass().isArray()) return null;
        String needle = String.valueOf(value).trim();
        if (needle.length() < 2) return null;
        int index = query.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
        if (index < 0) return null;
        int start = Math.max(0, index - 24);
        int end = Math.min(query.length(), index + needle.length() + 24);
        return query.substring(start, end);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw
            ? new LinkedHashMap<>((Map<String, Object>) raw) : new LinkedHashMap<>();
    }

    private Object first(Map<String, Object> source, String... keys) {
        if (source == null) return null;
        for (String key : keys) if (source.containsKey(key)) return source.get(key);
        return null;
    }

    private String templateId(Map<String, Object> template) {
        return text(first(template, "templateId", "template_id", "id", "code", "template"));
    }

    private String text(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Collection<?>) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private boolean hasValue(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    record BindingResult(List<Map<String, Object>> protocols,
                         String nativeToolCallId,
                         Map<String, Object> metrics,
                         String mode) {}
}
