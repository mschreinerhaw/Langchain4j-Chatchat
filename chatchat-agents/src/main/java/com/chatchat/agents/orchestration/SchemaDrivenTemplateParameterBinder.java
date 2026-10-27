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
    static final String SELECTION_FUNCTION = "select_templates_and_bind_parameters";

    private final ObjectMapper objectMapper;

    SchemaDrivenTemplateParameterBinder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** One model decision. Java checks identities and schemas, never relevance or coverage. */
    SelectionResult selectAndBind(ChatModel model, String query, List<Map<String,Object>> templates) {
        return selectAndBind(model,query,templates,Map.of());
    }

    SelectionResult selectAndBind(ChatModel model,String query,List<Map<String,Object>> templates,
                                  Map<String,Object> selectionContext) {
        List<String> ids=templates.stream().map(this::templateId).filter(Objects::nonNull).toList();
        Map<String,Map<String,Object>> candidates=selectedTemplates(templates,ids);
        if(candidates.isEmpty()) return new SelectionResult("NO_CANDIDATES",List.of(),List.of(),Map.of(),null);
        if(model==null) return new SelectionResult("MODEL_UNAVAILABLE",List.of(),List.of(),Map.of(),"Model is unavailable");
        try {
            List<Map<String,Object>> variants=new ArrayList<>(),catalog=new ArrayList<>();
            for(var template:candidates.values()) {
                Map<String,Object> fields=new LinkedHashMap<>();
                fields.put("templateId",Map.of("type","string","enum",List.of(templateId(template))));
                fields.put("parameters",bindingParameterSchema(template));
                fields.put("bindingId",Map.of("type","string"));
                String assetId=publishedAssetId(template);
                if(assetId!=null) fields.put("assetId",Map.of("type","string","enum",List.of(assetId)));
                variants.add(Map.of("type","object","properties",fields,
                    "required",List.of("templateId","parameters"),"additionalProperties",false));
                Map<String,Object> summary=new LinkedHashMap<>();
                summary.put("templateId",templateId(template));
                for(String key:List.of("title","description","assetType","businessGroup","asset","dependencySpec","outputSchema")) {
                    Object value=template.get(key);
                    if(value!=null) summary.put(key,value instanceof String text&&text.length()>800?text.substring(0,800):value);
                }
                catalog.add(summary);
            }
            var schema=Map.of("type","object","additionalProperties",false,
                "required",List.of("status","selections"),"properties",Map.of(
                    "status",Map.of("type","string","enum",List.of("SELECTED","NO_MATCH","NEEDS_INPUT")),
                    "selections",Map.of("type","array","maxItems",ToolCallBatchSchema.DEFAULT_MAX_CALLS,
                        "items",Map.of("oneOf",variants)),
                    "reason",Map.of("type","string"),
                    "missingAspects",Map.of("type","array","items",Map.of("type","string"))));
            ObjectNode node=objectMapper.createObjectNode();node.put("name",SELECTION_FUNCTION);
            node.put("description","Select returned templates and extract their parameters for the user's complete request.");
            node.set("parameters",objectMapper.valueToTree(schema));
            var spec=ToolSpecification.fromJson(objectMapper.writeValueAsString(node));
            var response=nativeCall(model,List.of(SystemMessage.from(
                "You own business interpretation, template relevance and coverage. Select all and only the returned templates needed "
                +"for the complete user request, including complementary sources and multiple assets when needed. "
                +"Treat IDs as opaque. Return exactly one designated function call with selections containing templateId and parameters. "
                +"Use assetId only when published in that template's contract; fixed binding assets are resolved by the supplier. "
                +"Extract parameter values from the user request. Omit unknown required values and use NEEDS_INPUT; do not invent them. "
                +"Use NO_MATCH with an empty selection when no candidate is suitable. Java validates identities, permissions and schemas only."),
                UserMessage.from("User request: "+Objects.toString(query,"")+"\nCandidates: "+objectMapper.writeValueAsString(catalog)
                    +"\nPublished scope and prior observations: "+objectMapper.writeValueAsString(selectionContext))),spec,ToolChoice.AUTO);
            AiMessage message=response==null?null:response.aiMessage();
            if(message==null||!message.hasToolExecutionRequests()||message.toolExecutionRequests().size()!=1
                    ||!SELECTION_FUNCTION.equals(message.toolExecutionRequests().get(0).name()))
                return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),Map.of(),"Expected one selection function call");
            Map<String,Object> decision=objectMapper.readValue(message.toolExecutionRequests().get(0).arguments(),new TypeReference<>(){});
            String status=text(decision.get("status"));
            if(!Set.of("SELECTED","NO_MATCH","NEEDS_INPUT").contains(Objects.toString(status,""))
                    ||!(decision.get("selections") instanceof Collection<?> selections))
                return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),decision,"Missing status or selections");
            if(selections.size()>ToolCallBatchSchema.DEFAULT_MAX_CALLS)
                return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),decision,"Selection execution limit exceeded");
            List<Map<String,Object>> bindings=new ArrayList<>();List<String> selected=new ArrayList<>();
            for(Object value:selections) {
                Map<String,Object> selection=map(value);String id=text(selection.get("templateId"));
                Map<String,Object> template=id==null?null:candidates.get(id.toLowerCase(Locale.ROOT));
                if(template==null) return new SelectionResult("INVALID_TEMPLATE_ID",List.of(),List.of(),decision,"Selected ID was not returned");
                String asset=text(selection.get("assetId"));
                if(asset!=null&&!asset.equals(publishedAssetId(template)))
                    return new SelectionResult("INVALID_ASSET_BINDING",List.of(),List.of(),decision,"Asset does not match published template binding");
                if(!(selection.get("parameters") instanceof Map<?,?>))
                    return new SelectionResult("INVALID_PARAMETERS",List.of(),List.of(),decision,"parameters must be an object");
                Map<String,Object> parameters=map(selection.get("parameters"));
                if(!map(parameterSchema(template).get("properties")).keySet().containsAll(parameters.keySet()))
                    return new SelectionResult("INVALID_PARAMETERS",List.of(),List.of(),decision,"Parameter field is not in the published schema");
                selected.add(templateId(template));
                Map<String,Object> binding=new LinkedHashMap<>();binding.put("template_id",templateId(template));
                binding.put("arguments",parameters);if(selection.get("bindingId")!=null)binding.put("binding_id",selection.get("bindingId"));
                bindings.add(binding);
            }
            if("NO_MATCH".equals(status)&&!selected.isEmpty()||"SELECTED".equals(status)&&selected.isEmpty())
                return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),decision,"status and selections disagree");
            List<Map<String,Object>> bound=protocols(bindings,query,candidates);
            if(bound.stream().anyMatch(protocol->protocol.get("unresolved_parameters") instanceof Collection<?> missing&&!missing.isEmpty()))
                return new SelectionResult("PARAMETERS_REQUIRED",List.copyOf(new LinkedHashSet<>(selected)),bound,decision,"Required parameter binding is incomplete");
            return new SelectionResult(status,List.copyOf(new LinkedHashSet<>(selected)),bound,decision,null);
        } catch(AgentBudgetExceededException|CancellationException failure) {throw failure;}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure) {
            return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),Map.of(),"Selection response is not valid JSON");
        }
        catch(Exception failure) {
            log.info("Template selection model call unavailable: {}",failure.getClass().getSimpleName());
            return new SelectionResult("MODEL_UNAVAILABLE",List.of(),List.of(),Map.of(),failure.getClass().getSimpleName());
        }
    }

    private String publishedAssetId(Map<String,Object> template) {
        return text(first(map(template.get("asset")),"id","assetId"));
    }

    record SelectionResult(String status,List<String> selectedIds,List<Map<String,Object>> protocols,
                           Map<String,Object> decision,String error) {}

    Optional<BindingResult> bind(ChatModel model,
                                 String userQuery,
                                 List<Map<String, Object>> returnedTemplates,
                                 List<String> selectedTemplateIds) {
        if (selectedTemplateIds == null || selectedTemplateIds.isEmpty()) return Optional.empty();
        var selected = selectedTemplates(returnedTemplates, selectedTemplateIds);
        if (selected.size() != new LinkedHashSet<>(selectedTemplateIds).size()) return Optional.empty();
        List<Map<String, Object>> fixed = selected.values().stream().filter(this::emptySchema).toList();
        if (fixed.isEmpty() || fixed.size() == selected.size()) {
            return bindNative(model, userQuery, returnedTemplates, selectedTemplateIds);
        }
        List<Map<String, Object>> requiringValues = selected.values().stream()
            .filter(template -> !emptySchema(template)).toList();
        var nativeBinding = bindNative(model, userQuery, requiringValues,
            requiringValues.stream().map(this::templateId).toList());
        List<Map<String, Object>> protocols = new ArrayList<>();
        for (var template : fixed) protocols.add(Map.of(
            "protocol_version", AgentProtocolCatalog.TEMPLATE_PARAMETER,
            "template_id", templateId(template), "arguments", Map.of(),
            "unresolved_parameters", List.of(), "generation_mode", "runtime_empty_schema"));
        if (nativeBinding.isPresent()) protocols.addAll(nativeBinding.get().protocols());
        else for (var template : requiringValues) protocols.add(Map.of(
            "protocol_version", AgentProtocolCatalog.TEMPLATE_PARAMETER,
            "template_id", templateId(template), "arguments", Map.of(),
            "unresolved_parameters", List.of(), "binding_error", "NATIVE_SCHEMA_BINDING_UNAVAILABLE"));
        Map<String, Object> metrics = new LinkedHashMap<>(nativeBinding.map(BindingResult::metrics).orElse(Map.of()));
        metrics.put("bindingCount", protocols.size());
        metrics.put("runtimeEmptySchemaCount", fixed.size());
        metrics.put("blockedBindingCount", nativeBinding.isPresent() ? 0 : requiringValues.size());
        return Optional.of(new BindingResult(List.copyOf(protocols),
            nativeBinding.map(BindingResult::nativeToolCallId).orElse(null), Map.copyOf(metrics), "controlled_schema_binding"));
    }

    private boolean emptySchema(Map<String, Object> template) {
        Map<String, Object> schema = parameterSchema(template);
        return "object".equals(schema.get("type")) && map(schema.get("properties")).isEmpty()
            && (!schema.containsKey("required")
                || schema.get("required") instanceof Collection<?> required && required.isEmpty());
    }

    private Optional<BindingResult> bindNative(ChatModel model,
                                 String userQuery,
                                 List<Map<String, Object>> returnedTemplates,
                                 List<String> selectedTemplateIds) {
        if (selectedTemplateIds == null || selectedTemplateIds.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Map<String, Object>> selected = selectedTemplates(returnedTemplates,
            selectedTemplateIds);
        if (selected.size() != new LinkedHashSet<>(selectedTemplateIds).size()) {
            return Optional.empty();
        }
        if (selected.values().stream().allMatch(this::emptySchema)) {
            List<Map<String, Object>> protocols = new ArrayList<>();
            for (Map<String, Object> template : selected.values()) {
                protocols.add(Map.of("protocol_version", AgentProtocolCatalog.TEMPLATE_PARAMETER,
                    "template_id", templateId(template), "arguments", Map.of(),
                    "unresolved_parameters", List.of(), "generation_mode", "runtime_empty_schema"));
            }
            return Optional.of(new BindingResult(List.copyOf(protocols), null,
                Map.of("bindingCount", protocols.size(), "evidenceVerifiedParameterCount", 0),
                "runtime_empty_schema"));
        }
        if (model == null) return Optional.empty();
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
            if (message == null || !message.hasToolExecutionRequests()) {
                log.info("Controlled parameter binding rejected: no designated native function call");
                return Optional.empty();
            }
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
            if (!covered.equals(selected.keySet())) {
                log.info("Controlled parameter binding rejected: selectedCount={}, coveredCount={}", selected.size(), covered.size());
                return Optional.empty();
            }
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
            log.info("Schema-driven template parameter Function Calling unavailable; execution remains blocked: {}",
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
            for (String required : requiredWithoutDefaults(parameterSchema(template), schemaProperties)) {
                if (!audited.containsKey(required) && !unresolved.contains(required)) unresolved.add(required);
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

    /** The binding proposal may omit unknown fields. Executor-required fields are enforced by
     * Runtime after evidence verification, without forcing the model to invent their values. */
    private Map<String, Object> bindingParameterSchema(Map<String, Object> template) {
        Map<String, Object> schema = new LinkedHashMap<>(parameterSchema(template));
        schema.remove("required");
        return Map.copyOf(schema);
    }

    private String systemPrompt() {
        return "You bind user language to Runtime-published JSON Schemas. The template and field names are opaque. "
            + "Use only schema descriptions and literal user evidence. Never invent identifiers, dates, defaults or fields. "
            + "Emit a binding for every selected template even when values are unknown; omit unknown arguments. "
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
