package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.model.AgentBudgetExceededException;
import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.agents.runtime.batch.ToolCallBatchSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        var metrics=new LinkedHashMap<String,Object>();
        metrics.put("selectionModelCalls",0);metrics.put("selectionUsageAvailable",false);
        long started=System.nanoTime();
        var result=selectionDecision(model,query,templates,selectionContext,metrics);
        if(model!=null && Set.of("INVALID_MODEL_RESPONSE","INVALID_TEMPLATE_ID","INVALID_ASSET_BINDING","INVALID_PARAMETERS").contains(result.status())) {
            var previous=result;
            var correction=new LinkedHashMap<String,Object>(selectionContext);
            correction.put("protocolCorrection",Map.of("errorCode",result.status(),"previousDecision",result.decision(),
                "instruction","Repair only the response protocol against the SAME request, candidates and schemas. Preserve every valid prior selection and its parameters. Do not change business scope or invent IDs."));
            metrics.put("selectionProtocolCorrections",1);metrics.put("selectionInitialErrorCode",result.status());
            result=selectionDecision(model,query,templates,correction,metrics);
            if(Set.of("SELECTED","NO_MATCH").contains(result.status())
                    && !preservesValidSelections(previous.decision(),result.decision(),templates))
                result=new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),result.decision(),"Protocol correction changed a valid prior selection");
        }
        metrics.put("selectionDurationMs",(System.nanoTime()-started)/1_000_000);
        return new SelectionResult(result.status(),result.selectedIds(),result.protocols(),result.decision(),result.error(),metrics);
    }

    private SelectionResult selectionDecision(ChatModel model,String query,List<Map<String,Object>> templates,
            Map<String,Object> selectionContext,Map<String,Object> metrics) {
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
                fields.put("parameterEvidence", Map.of("type", "object", "properties",
                    map(parameterSchema(template).get("properties")).keySet().stream().collect(
                        java.util.stream.Collectors.toMap(name -> name, name -> Map.of("type", "string"))),
                    "additionalProperties", false));
                fields.put("bindingId",Map.of("type","string"));
                fields.put("unmappedConstraints",Map.of("type","array","items",Map.of("type","string")));
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
            metrics.put("selectionModelCalls",((Number)metrics.get("selectionModelCalls")).intValue()+1);
            long modelStarted=System.nanoTime();
            ChatResponse response;
            try { response=nativeCall(model,List.of(SystemMessage.from(
                "You own business interpretation, template relevance and coverage. Select all and only the returned templates needed "
                +"for the complete user request, including complementary sources and multiple assets when needed. "
                +"Treat IDs as opaque. Return exactly one designated function call with selections containing templateId and parameters. "
                +"Use assetId only when published in that template's contract; fixed binding assets are resolved by the supplier. "
                +"Infer parameter values from the user request, including schema-valid arrays and normalized dates. "
                +"For values not literally present, provide parameterEvidence mapping each parameter to an exact supporting quote from the user request. "
                +"Omit unknown values. Java applies published defaults and omits optional fields; parameters may be {}. "
                +"Do not request another extraction to fill defaults. Only required fields without valid defaults need input. "
                +"If a requested entity or time constraint cannot be mapped reliably, include its exact user quote in unmappedConstraints. "
                +"Technical executability with defaults does not establish that results match the requested business scope. Do not invent values. "
                +"Use NO_MATCH with an empty selection when no candidate is suitable. Java validates identities, permissions and schemas only."),
                UserMessage.from("User request: "+Objects.toString(query,"")+"\nCandidates: "+objectMapper.writeValueAsString(catalog)
                    +"\nPublished scope and prior observations: "+objectMapper.writeValueAsString(selectionContext))),spec,ToolChoice.AUTO);
            } finally {metrics.put("selectionModelDurationMs",((Number)metrics.getOrDefault("selectionModelDurationMs",0L)).longValue()
                    +(System.nanoTime()-modelStarted)/1_000_000);}
            var usage=com.chatchat.agents.orchestration.model.ModelUsageMetrics.observed(response==null?null:response.tokenUsage());
            if(usage.containsKey("inputTokens") && usage.containsKey("outputTokens"))
                metrics.put("selectionUsageKnownCalls",((Number)metrics.getOrDefault("selectionUsageKnownCalls",0)).intValue()+1);
            metrics.put("selectionUsageAvailable",metrics.getOrDefault("selectionUsageKnownCalls",0).equals(metrics.get("selectionModelCalls")));
            for(String token:List.of("inputTokens","outputTokens"))if(usage.containsKey(token)) {
                String field="inputTokens".equals(token)?"selectionInputTokens":"selectionOutputTokens";
                metrics.put(field,((Number)metrics.getOrDefault(field,0L)).longValue()+usage.get(token));
            }
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
                if(selection.get("parameters")!=null && !(selection.get("parameters") instanceof Map<?,?>))
                    return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),decision,"parameters must be an object");
                Map<String,Object> parameters=map(selection.get("parameters"));
                selected.add(templateId(template));
                Map<String,Object> binding=new LinkedHashMap<>();binding.put("template_id",templateId(template));
                binding.put("arguments",parameters);if(selection.get("bindingId")!=null)binding.put("binding_id",selection.get("bindingId"));
                binding.put("parameter_evidence", map(selection.get("parameterEvidence")));
                binding.put("unmapped_constraints", selection.getOrDefault("unmappedConstraints",List.of()));
                bindings.add(binding);
            }
            if("NO_MATCH".equals(status)&&!selected.isEmpty()||"SELECTED".equals(status)&&selected.isEmpty())
                return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),decision,"status and selections disagree");
            List<Map<String,Object>> bound=protocols(bindings,query,candidates);
            if(!bound.isEmpty() && bound.stream().allMatch(protocol->protocol.containsKey("binding_error"))) {
                boolean invalidDefault=bound.stream().anyMatch(protocol->String.valueOf(protocol.get("binding_error")).contains("INVALID_TEMPLATE_DEFAULT"));
                return new SelectionResult(invalidDefault?"INVALID_TEMPLATE_DEFAULT":"PARAMETERS_REQUIRED",List.copyOf(new LinkedHashSet<>(selected)),bound,decision,"No selected invocation has valid executable parameters");
            }
            return new SelectionResult(selected.isEmpty()?status:"SELECTED",List.copyOf(new LinkedHashSet<>(selected)),bound,decision,null);
        } catch(AgentBudgetExceededException|CancellationException failure) {throw failure;}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure) {
            return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),Map.of(),"Selection response is not valid JSON");
        }
        catch(Exception failure) {
            log.info("Template selection model call unavailable: {}",failure.getClass().getSimpleName());
            return new SelectionResult("MODEL_UNAVAILABLE",List.of(),List.of(),Map.of(),failure.getClass().getSimpleName());
        }
    }

    private boolean preservesValidSelections(Map<String,Object> previous,Map<String,Object> corrected,List<Map<String,Object>> templates) {
        if(!(previous.get("selections") instanceof Collection<?> selections))return true;
        var byId=selectedTemplates(templates,templates.stream().map(this::templateId).filter(Objects::nonNull).toList());
        Collection<?> next=corrected.get("selections") instanceof Collection<?> values?values:List.of();
        for(Object value:selections) {
            var prior=map(value);String id=text(prior.get("templateId"));
            var candidate=id==null?null:byId.get(id.toLowerCase(Locale.ROOT));
            if(candidate==null || !(prior.get("parameters") instanceof Map<?,?>)
                    || !map(parameterSchema(candidate).get("properties")).keySet().containsAll(map(prior.get("parameters")).keySet())
                    || prior.get("assetId")!=null&&!Objects.equals(prior.get("assetId"),publishedAssetId(candidate)))continue;
            if(next.stream().map(this::map).noneMatch(item->Objects.equals(id,item.get("templateId"))
                    && Objects.equals(prior.get("parameters"),item.get("parameters"))))return false;
        }
        return true;
    }

    private String publishedAssetId(Map<String,Object> template) {
        return text(first(map(template.get("asset")),"id","assetId"));
    }

    record SelectionResult(String status,List<String> selectedIds,List<Map<String,Object>> protocols,
                           Map<String,Object> decision,String error,Map<String,Object> metrics) {
        SelectionResult(String status,List<String> selectedIds,List<Map<String,Object>> protocols,
                        Map<String,Object> decision,String error) {
            this(status,selectedIds,protocols,decision,error,Map.of());
        }
        SelectionResult {metrics=Map.copyOf(metrics);}
    }

    private Set<String> requiredWithoutDefaults(Map<String, Object> schema,
                                                Map<String, Object> properties) {
        Object requiredValue = schema.get("required");
        if (!(requiredValue instanceof Collection<?> required)) return Set.of();
        return required.stream()
            .map(this::text)
            .filter(Objects::nonNull)
            .filter(name -> {var field=map(properties.get(name));return !field.containsKey("default")&&!field.containsKey("defaultValue")&&!field.containsKey("default_value");})
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
            Map<String, Object> supportingQuotes = map(binding.get("parameter_evidence"));
            Map<String, Object> audited = new LinkedHashMap<>();
            List<String> unresolved = new ArrayList<>();
            for (Map.Entry<String, Object> argument : proposed.entrySet()) {
                if (!schemaProperties.containsKey(argument.getKey()) || !hasValue(argument.getValue())) continue;
                String quote = exactEvidenceQuote(userQuery, argument.getValue());
                boolean interpreted = quote == null;
                if (interpreted) {
                    String proposedQuote = text(supportingQuotes.get(argument.getKey()));
                    if (proposedQuote != null && userQuery != null && userQuery.contains(proposedQuote)) quote = proposedQuote;
                }
                if (quote == null) {
                    unresolved.add(argument.getKey());
                    continue;
                }
                audited.put(argument.getKey(), Map.of(
                    "value", argument.getValue(),
                    "source", "user_query",
                    "evidence", interpreted
                        ? Map.of("quote", quote, "generated_by", "native_function_calling", "interpretation", "model")
                        : Map.of("quote", quote, "generated_by", "native_function_calling")
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
            protocol.put("unmapped_constraints",binding.getOrDefault("unmapped_constraints",List.of()));
            Map<String,Object> verified=new LinkedHashMap<>();
            audited.forEach((name,entry)->verified.put(name,map(entry).get("value")));
            var compilation=new com.chatchat.agents.runtime.toolcall.ToolArgumentCompiler().compileTemplate(verified,parameterSchema(template));
            protocol.put("binding_status",compilation.valid()?"READY":"INVALID_TOOL_ARGUMENTS");
            protocol.put("dropped_parameter_overrides",proposed.keySet().stream().filter(name->!audited.containsKey(name)).toList());
            List<Map<String,Object>> rejected=new ArrayList<>();
            proposed.forEach((name,proposal)->{if(!audited.containsKey(name)) {
                var dropped=new LinkedHashMap<String,Object>();dropped.put("field",name);dropped.put("value",proposal);
                dropped.put("reasonCode",schemaProperties.containsKey(name)?"UNVERIFIED_PARAMETER_EVIDENCE":"UNDECLARED_PARAMETER");
                rejected.add(java.util.Collections.unmodifiableMap(dropped));
            }});
            protocol.put("rejected_parameter_overrides",List.copyOf(rejected));
            if(!compilation.valid())protocol.put("binding_error",Map.of("code",compilation.status(),"errors",compilation.validationErrors()));
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

    private String exactEvidenceQuote(String query, Object value) {
        if (query == null || value == null || value instanceof Map<?, ?>
            || value instanceof Collection<?> || value.getClass().isArray()) return null;
        String needle = String.valueOf(value).trim();
        if (needle.isEmpty()) return null;
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

}
