package com.chatchat.agents.orchestration;

import com.chatchat.agents.model.ModelFailureCategory;
import com.chatchat.agents.orchestration.model.AgentBudgetExceededException;
import com.chatchat.agents.orchestration.model.ModelPhaseBudgetExceededException;
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
import dev.langchain4j.model.output.FinishReason;
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

    static final String BINDING_FUNCTION = "bind_published_template_parameters";
    static final String REPAIR_FUNCTION = "repair_template_parameter_fields";
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
        return selectAndBind(model,query,templates,selectionContext,()->{});
    }
    SelectionResult selectAndBind(ChatModel model,String query,List<Map<String,Object>> templates,
                                  Map<String,Object> selectionContext,Runnable cancellationGuard) {
        return bind(model,query,templates,selectionContext,cancellationGuard,false);
    }

    /** Every published identity is authoritative. Model output supplies parameter bindings only. */
    SelectionResult bindPublished(ChatModel model,String query,List<Map<String,Object>> templates,
                                  Map<String,Object> context,Runnable cancellationGuard) {
        var result=bind(model,query,templates,context,cancellationGuard,true);
        if(!result.selectedIds().isEmpty())return result;
        var published=templates.stream().map(this::templateId).filter(Objects::nonNull).toList();
        var ids=selectedTemplates(templates,published).values().stream().map(this::templateId).toList();
        if(ids.isEmpty()||ids.size()>ToolCallBatchSchema.DEFAULT_MAX_CALLS)return result;
        var blocked=ids.stream().map(id->blockedPublishedBinding(id,result.status())).toList();
        return new SelectionResult(result.status(),ids,blocked,result.decision(),result.error(),result.metrics());
    }

    private Map<String,Object> blockedPublishedBinding(String id,String code) {
        return Map.of("protocol_version",AgentProtocolCatalog.TEMPLATE_PARAMETER,
            "template_id",id,"arguments",Map.of(),"binding_status","INVALID_TOOL_ARGUMENTS",
            "binding_error",Map.of("code",code));
    }

    private SelectionResult bind(ChatModel model,String query,List<Map<String,Object>> templates,
            Map<String,Object> selectionContext,Runnable cancellationGuard,boolean bindingOnly) {
        cancellationGuard.run();
        if(query!=null && query.length()>6000)return new SelectionResult("NEEDS_INPUT",List.of(),List.of(),
            Map.of("requestViewComplete",false),
            "Separate the complete business-data objective and scope from long supplied material before binding a query; omitted constraints must not become template defaults",
            Map.of("selectionModelCalls",0,"selectionUsageAvailable",false));
        var metrics=new LinkedHashMap<String,Object>();
        metrics.put("selectionModelCalls",0);metrics.put("selectionUsageAvailable",false);
        long started=System.nanoTime();
        var result=selectionDecision(model,query,templates,selectionContext,metrics,cancellationGuard,bindingOnly);
        cancellationGuard.run();
        if(model!=null && Set.of("INVALID_MODEL_RESPONSE","INVALID_TEMPLATE_ID","INVALID_ASSET_BINDING").contains(result.status())) {
            var previous=result;
            var correction=new LinkedHashMap<String,Object>(selectionContext);
            correction.put("protocolCorrection",Map.of("errorCode",result.status(),"previousDecision",result.decision(),
                "instruction",bindingOnly
                    ? "Repair the parameter response against the same request and published schemas. Preserve every valid binding."
                    : "Repair only the response protocol against the SAME request, candidates and schemas. Preserve every valid prior selection and its parameters. Do not change business scope or invent IDs."));
            metrics.put("selectionProtocolCorrections",1);metrics.put("selectionInitialErrorCode",result.status());
            result=selectionDecision(model,query,templates,correction,metrics,cancellationGuard,bindingOnly);
            cancellationGuard.run();
            if(Set.of("SELECTED","NO_MATCH").contains(result.status())
                    && !preservesValidSelections(previous.decision(),result.decision(),templates,bindingOnly?"bindings":"selections"))
                result=new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),result.decision(),"Protocol correction changed a valid prior selection");
        }
        metrics.put("selectionDurationMs",(System.nanoTime()-started)/1_000_000);
        return new SelectionResult(result.status(),result.selectedIds(),result.protocols(),result.decision(),result.error(),metrics);
    }

    private SelectionResult selectionDecision(ChatModel model,String query,List<Map<String,Object>> templates,
            Map<String,Object> selectionContext,Map<String,Object> metrics,Runnable cancellationGuard,boolean bindingOnly) {
        List<String> candidateIds=templates.stream().map(this::templateId).filter(Objects::nonNull).toList();
        Map<String,Map<String,Object>> candidates=selectedTemplates(templates,candidateIds);
        if(candidates.isEmpty()) return new SelectionResult("NO_CANDIDATES",List.of(),List.of(),Map.of(),null);
        if(bindingOnly && candidates.size()>ToolCallBatchSchema.DEFAULT_MAX_CALLS)
            return new SelectionResult("BATCH_LIMIT_EXCEEDED",List.of(),List.of(),Map.of(),"Published scope exceeds the execution batch limit");
        if(model==null) return new SelectionResult("MODEL_UNAVAILABLE",List.of(),List.of(),Map.of(),"Model is unavailable");
        try {
            String functionName=bindingOnly?BINDING_FUNCTION:SELECTION_FUNCTION;
            String itemsName=bindingOnly?"bindings":"selections";
            var modelContext = TemplateRecordsetBindings.modelContext(selectionContext);
            boolean recordsetReferences = modelContext.get("completedRecordsets") instanceof List<?> records && !records.isEmpty();
            List<Map<String,Object>> variants=new ArrayList<>(),catalog=new ArrayList<>();
            for(var template:candidates.values()) {
                Map<String,Object> fields=new LinkedHashMap<>();
                fields.put("templateId",Map.of("type","string","enum",List.of(templateId(template))));
                Map<String,Object> parameters = bindingParameterSchema(template);
                fields.put("parameters",parameters);
                var parameterNames = map(parameters.get("properties")).keySet();
                if (!parameterNames.isEmpty()) {
                    Map<String,Object> evidence = new LinkedHashMap<>();
                    parameterNames.forEach(name -> evidence.put(name, Map.of("type","string")));
                    fields.put("parameterEvidence", Map.of("type","object","properties",evidence,"additionalProperties",false));
                    if (recordsetReferences) {
                        Map<String,Object> references = new LinkedHashMap<>();
                        parameterNames.forEach(name -> references.put(name, Map.of("type","object","properties",
                            Map.of("stepId",Map.of("type","integer"),"outputPath",Map.of("type","string")),
                            "required",List.of("stepId","outputPath"),"additionalProperties",false)));
                        fields.put("parameterBindings", Map.of("type","object","properties",references,"additionalProperties",false));
                    }
                }
                fields.put("bindingId",Map.of("type","string"));
                fields.put("unmappedConstraints",Map.of("type","array","items",Map.of("type","string")));
                String assetId=publishedAssetId(template);
                if(!bindingOnly&&assetId!=null) fields.put("assetId",Map.of("type","string","enum",List.of(assetId)));
                variants.add(Map.of("type","object","properties",fields,
                    "required",List.of("templateId","parameters"),"additionalProperties",false));
                Map<String,Object> summary=new LinkedHashMap<>();
                summary.put("templateId",templateId(template));
                if (bindingOnly && assetId != null) summary.put("assetId",assetId);
                var summaryFields=bindingOnly?List.of("title")
                    :List.of("title","description","assetType","businessGroup","asset","dependencySpec","outputSchema");
                for(String key:summaryFields) {
                    Object value=template.get(key);
                    if(value!=null && !("description".equals(key) && value.equals(template.get("title")))) summary.put(key,value instanceof String text&&text.length()>800?text.substring(0,800):value);
                }
                catalog.add(summary);
            }
            // The parameter contract can be sent once even when assets differ.
            // Runtime permits sharing values only within the same asset.
            int ungroupedVariantCount=variants.size();
            metrics.put("selectionUngroupedVariantChars",objectMapper.writeValueAsString(variants).length());
            variants=groupEquivalentVariants(variants);
            Map<String, Set<String>> sharedGroups = new LinkedHashMap<>();
            for (var variant : variants) {
                var fields = map(variant.get("properties"));
                var ids = map(map(fields.get("templateIds")).get("items")).get("enum");
                if (ids instanceof List<?> group) {
                    var members = new LinkedHashSet<String>();
                    group.forEach(id -> members.add(String.valueOf(id)));
                    members.forEach(id -> sharedGroups.put(id, members));
                }
            }
            metrics.put("selectionGroupedVariantChars",objectMapper.writeValueAsString(variants).length());
            String catalogJson=objectMapper.writeValueAsString(catalog);
            metrics.put("selectionCatalogChars",catalogJson.length());
            String contextJson=objectMapper.writeValueAsString(modelContext);
            metrics.put("selectionContextChars",contextJson.length());
            var properties=new LinkedHashMap<String,Object>();
            properties.put(itemsName,Map.of("type","array","maxItems",ToolCallBatchSchema.DEFAULT_MAX_CALLS,
                "items",Map.of("oneOf",variants)));
            if(!bindingOnly) {
                properties.put("status",Map.of("type","string","enum",List.of("SELECTED","NO_MATCH","NEEDS_INPUT")));
                properties.put("reason",Map.of("type","string"));
                properties.put("missingAspects",Map.of("type","array","items",Map.of("type","string")));
            }
            var schema=Map.of("type","object","additionalProperties",false,
                "required",bindingOnly?List.of(itemsName):List.of("status",itemsName),"properties",properties);
            ObjectNode node=objectMapper.createObjectNode();
            node.put("name",functionName);
            node.put("description",bindingOnly?"Bind parameters for every published template."
                :"Select returned templates and extract their parameters for the user's complete request.");
            node.set("parameters",objectMapper.valueToTree(schema));
            var spec=ToolSpecification.fromJson(objectMapper.writeValueAsString(node));
            metrics.put("selectionCandidateCount",catalog.size());
            metrics.put("selectionUngroupedVariantCount",ungroupedVariantCount);
            metrics.put("selectionSchemaVariantCount",variants.size());
            metrics.put("selectionFunctionSchemaChars",spec.toJson().length());
            String system = (bindingOnly
                ? "Bind every published template ID with one function call. Use parameters={} for unknown values. "
                : "Use one function call to select all and only the relevant templates for the full request, including complementary sources and assets. "
                    + "IDs are opaque. Supply assetId only for its published template. If none match, return NO_MATCH with []. ")
                + (sharedGroups.isEmpty() ? "" : "Use templateIds to bind one value set to selected IDs with the same contract and asset. "
                    + "Use separate entries for different values or scopes. Include only applicable IDs. ")
                + "Bind user values in schema types (including arrays and dates). Quote exact user evidence for inferred values only. "
                + "Omit unknowns and nulls. Runtime applies published defaults and checks required fields; defaults do not verify scope. "
                + "Put exact quotes for unmapped entities or dates in unmappedConstraints. Never invent values. "
                + (recordsetReferences ? "Bind matching completedRecordsets by stepId/outputPath only; omit those fields from parameters. Never copy previews. " : "")
                + "Omit optional output fields unless needed.";
            String user = "User request: " + Objects.toString(query, "") + "\nTemplates: " + catalogJson
                + "\nScope and observations: " + contextJson;
            metrics.put("selectionSystemChars",system.length());
            metrics.put("selectionUserChars",user.length());
            ChatResponse response = callModel(model,system,user,spec,metrics,cancellationGuard);
            AiMessage message=response==null?null:response.aiMessage();
            metrics.put("selectionResponseTextChars",message==null||message.text()==null?0:message.text().length());
            metrics.put("selectionResponseArgumentChars",message==null||!message.hasToolExecutionRequests()?0:
                message.toolExecutionRequests().stream().mapToInt(call->call.arguments()==null?0:call.arguments().length()).sum());
            if (message == null || !message.hasToolExecutionRequests()
                    || response.finishReason() == FinishReason.LENGTH || response.finishReason() == FinishReason.CONTENT_FILTER)
                return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),Map.of(),"Expected one complete selection function call");
            var calls = message.toolExecutionRequests();
            var first = calls.get(0);
            // These calls propose a binding; they do not execute the tool. Identical
            // proposals from the same response can share one validation and one execution batch.
            if (!functionName.equals(first.name()) || calls.size() > 1 && (!bindingOnly ||
                    calls.stream().anyMatch(call -> !first.name().equals(call.name())
                        || !Objects.equals(first.arguments(), call.arguments()))))
                return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),Map.of(),"Expected one selection function call");
            if (calls.size() > 1) metrics.put("duplicateBindingProposals", calls.size() - 1);
            Map<String,Object> decision=objectMapper.readValue(first.arguments(),new TypeReference<>(){});
            String status=bindingOnly?"SELECTED":text(decision.get("status"));
            if((!bindingOnly&&!Set.of("SELECTED","NO_MATCH","NEEDS_INPUT").contains(Objects.toString(status,"")))
                    ||!(decision.get(itemsName) instanceof Collection<?> selections))
                return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),decision,"Missing required template decision fields");
            if(selections.size()>ToolCallBatchSchema.DEFAULT_MAX_CALLS)
                return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),decision,"Selection execution limit exceeded");
            List<Map<String,Object>> expanded = new ArrayList<>();
            for (Object value : selections) {
                var entry = map(value);
                if (!entry.containsKey("templateIds")) {
                    expanded.add(entry);
                    continue;
                }
                if (entry.containsKey("templateId") || !(entry.get("templateIds") instanceof List<?> ids)
                        || ids.isEmpty() || ids.size() > ToolCallBatchSchema.DEFAULT_MAX_CALLS
                        || !sharedGroups.getOrDefault(String.valueOf(ids.get(0)), Set.of()).containsAll(ids)
                        || new LinkedHashSet<>(ids).size() != ids.size())
                    return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),decision,"Invalid shared parameter group");
                String firstAsset = publishedAssetId(candidates.get(String.valueOf(ids.get(0)).toLowerCase(Locale.ROOT)));
                if (ids.stream().anyMatch(id -> !Objects.equals(firstAsset,
                        publishedAssetId(candidates.get(String.valueOf(id).toLowerCase(Locale.ROOT))))))
                    return new SelectionResult("INVALID_ASSET_BINDING",List.of(),List.of(),decision,"Shared parameters cross published asset scopes");
                for (Object id : ids) {
                    var item = new LinkedHashMap<>(entry);
                    item.remove("templateIds");
                    item.remove("bindingId"); // Each expanded invocation gets its own Runtime identity.
                    item.put("templateId",id);
                    expanded.add(item);
                }
                if (expanded.size() > ToolCallBatchSchema.DEFAULT_MAX_CALLS)
                    return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),decision,"Expanded binding limit exceeded");
            }
            if (expanded.size() > ToolCallBatchSchema.DEFAULT_MAX_CALLS)
                return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),decision,"Expanded binding limit exceeded");
            List<Map<String,Object>> bindings=new ArrayList<>();List<String> selected=new ArrayList<>();
            for(Object value:expanded) {
                Map<String,Object> selection=map(value);String id=text(selection.get("templateId"));
                Map<String,Object> template=id==null?null:candidates.get(id.toLowerCase(Locale.ROOT));
                if(template==null) return new SelectionResult("INVALID_TEMPLATE_ID",List.of(),List.of(),decision,"Selected ID was not returned");
                String asset=text(selection.get("assetId"));
                if(asset!=null&&(bindingOnly||!asset.equals(publishedAssetId(template))))
                    return new SelectionResult("INVALID_ASSET_BINDING",List.of(),List.of(),decision,"Asset does not match published template binding");
                boolean invalidParameters = (bindingOnly || selection.get("parameters") != null)
                    && !(selection.get("parameters") instanceof Map<?,?>);
                Map<String,Object> parameters=map(selection.get("parameters"));
                selected.add(templateId(template));
                Map<String,Object> binding=new LinkedHashMap<>();binding.put("template_id",templateId(template));
                binding.put("arguments",parameters);if(selection.get("bindingId")!=null)binding.put("binding_id",selection.get("bindingId"));
                if (invalidParameters) binding.put("invalid_parameter_shape",true);
                binding.put("parameter_evidence", map(selection.get("parameterEvidence")));
                binding.put("parameter_bindings", map(selection.get("parameterBindings")));
                binding.put("unmapped_constraints", selection.getOrDefault("unmappedConstraints",List.of()));
                bindings.add(binding);
            }
            if(!bindingOnly&&("NO_MATCH".equals(status)&&!selected.isEmpty()||"SELECTED".equals(status)&&selected.isEmpty()))
                return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),decision,"status and selections disagree");
            List<Map<String,Object>> bound=protocols(bindings,query,candidates,selectionContext);
            metrics.put("selectionSharedBindingsReused",expanded.size() - selections.size());
            if (bound.stream().anyMatch(protocol -> protocol.containsKey("binding_error"))
                    && !metrics.containsKey("selectionFieldRepairCalls")) {
                var repaired = repairBindings(model,query,candidates,selectionContext,bindings,metrics,cancellationGuard);
                if (repaired != bindings) bound = protocols(repaired,query,candidates,selectionContext);
            }
            if(bindingOnly) {
                Set<String> covered=new LinkedHashSet<>(selected);
                List<String> missing=candidates.values().stream().map(this::templateId)
                    .filter(id->!covered.contains(id)).toList();
                if(bound.size()+missing.size()>ToolCallBatchSchema.DEFAULT_MAX_CALLS)
                    return new SelectionResult("INVALID_MODEL_RESPONSE",List.of(),List.of(),decision,"Bindings exceed the published batch limit");
                for(String id:missing) {
                    bound.add(blockedPublishedBinding(id,"PUBLISHED_TEMPLATE_BINDING_MISSING"));
                }
                selected=new ArrayList<>(candidates.values().stream().map(this::templateId).toList());
            }
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
            String category = failure instanceof ModelPhaseBudgetExceededException ? "MODEL_PHASE_BUDGET_EXHAUSTED"
                : ModelFailureCategory.classify(failure).name();
            metrics.put("selectionModelFailureCategory",category);
            log.info("Template selection model call unavailable category={} exception={}",category,failure.getClass().getSimpleName());
            return new SelectionResult("MODEL_UNAVAILABLE",List.of(),List.of(),Map.of(),
                category + " (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private List<Map<String,Object>> groupEquivalentVariants(List<Map<String,Object>> variants) {
        Map<Map<String,Object>,List<String>> groups=new LinkedHashMap<>();
        Map<String,Object> assetsByTemplate = new LinkedHashMap<>();
        for(var variant:variants) {
            var fields=map(variant.get("properties"));
            var id=map(fields.remove("templateId"));
            Object asset = fields.remove("assetId");
            var identity=new LinkedHashMap<>(variant);
            identity.put("properties",fields);
            var ids=groups.computeIfAbsent(identity,ignored->new ArrayList<>());
            if (id.get("enum") instanceof Collection<?> values) for (Object value : values) {
                String key = String.valueOf(value);
                ids.add(key);
                if (asset != null) assetsByTemplate.put(key,asset);
            }
        }
        return groups.entrySet().stream().map(group->{
            var variant=new LinkedHashMap<>(group.getKey());
            var fields=new LinkedHashMap<String,Object>();
            List<String> ids = group.getValue();
            if (ids.size() > 1 && !map(map(map(variant.get("properties")).get("parameters")).get("properties")).isEmpty()) {
                fields.put("templateIds",Map.of("type","array","minItems",1,"maxItems",ids.size(),
                    "uniqueItems",true,"items",Map.of("type","string","enum",List.copyOf(ids))));
                variant.put("required",List.of("templateIds","parameters"));
            } else {
                fields.put("templateId",Map.of("type","string","enum",List.copyOf(ids)));
            }
            var assets = new LinkedHashSet<String>();
            for (String id : ids) {
                if (map(assetsByTemplate.get(id)).get("enum") instanceof Collection<?> values)
                    values.forEach(value -> assets.add(String.valueOf(value)));
            }
            if (!assets.isEmpty()) fields.put("assetId",Map.of("type","string","enum",List.copyOf(assets)));
            fields.putAll(map(variant.get("properties")));
            variant.put("properties",fields);
            return (Map<String,Object>)variant;
        }).toList();
    }

    private boolean preservesValidSelections(Map<String,Object> previous,Map<String,Object> corrected,List<Map<String,Object>> templates,String itemsName) {
        if(!(previous.get(itemsName) instanceof Collection<?> selections))return true;
        var byId=selectedTemplates(templates,templates.stream().map(this::templateId).filter(Objects::nonNull).toList());
        List<Map<String,Object>> next = decisionItems(corrected.get(itemsName));
        for(Object value:decisionItems(selections)) {
            var prior=map(value);String id=text(prior.get("templateId"));
            var candidate=id==null?null:byId.get(id.toLowerCase(Locale.ROOT));
            if(candidate==null || !(prior.get("parameters") instanceof Map<?,?>)
                    || !map(parameterSchema(candidate).get("properties")).keySet().containsAll(map(prior.get("parameters")).keySet())
                    || prior.get("assetId")!=null&&!Objects.equals(prior.get("assetId"),publishedAssetId(candidate)))continue;
            if(next.stream().noneMatch(item->Objects.equals(id,item.get("templateId"))
                    && Objects.equals(prior.get("parameters"),item.get("parameters"))))return false;
        }
        return true;
    }

    private List<Map<String,Object>> decisionItems(Object value) {
        if (!(value instanceof Collection<?> list)) return List.of();
        List<Map<String,Object>> items = new ArrayList<>();
        for (Object raw : list) {
            var entry = map(raw);
            if (entry.get("templateIds") instanceof List<?> ids) {
                for (Object id : ids) {
                    var item = new LinkedHashMap<>(entry);
                    item.put("templateId",id);
                    items.add(item);
                }
            } else {
                items.add(entry);
            }
        }
        return items;
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

    private ChatResponse callModel(ChatModel model,String system,String user,ToolSpecification spec,
                                   Map<String,Object> metrics,Runnable cancellationGuard) {
        cancellationGuard.run();
        metrics.put("selectionModelCalls",((Number)metrics.get("selectionModelCalls")).intValue()+1);
        metrics.put("selectionUsageAvailable",false);
        long started = System.nanoTime();
        ChatResponse response;
        try {
            response = model.chat(ChatRequest.builder()
                .messages(SystemMessage.from(system),UserMessage.from(user))
                .toolSpecifications(List.of(spec)).toolChoice(ToolChoice.AUTO).build());
        } finally {
            metrics.put("selectionModelDurationMs",((Number)metrics.getOrDefault("selectionModelDurationMs",0L)).longValue()
                +(System.nanoTime()-started)/1_000_000);
        }
        var usage = com.chatchat.agents.orchestration.model.ModelUsageMetrics.observed(response==null?null:response.tokenUsage());
        if (usage.containsKey("inputTokens") && usage.containsKey("outputTokens"))
            metrics.put("selectionUsageKnownCalls",((Number)metrics.getOrDefault("selectionUsageKnownCalls",0)).intValue()+1);
        metrics.put("selectionUsageAvailable",metrics.getOrDefault("selectionUsageKnownCalls",0).equals(metrics.get("selectionModelCalls")));
        for (String token : List.of("inputTokens","outputTokens")) if (usage.containsKey(token)) {
            String field = "inputTokens".equals(token)?"selectionInputTokens":"selectionOutputTokens";
            metrics.put(field,((Number)metrics.getOrDefault(field,0L)).longValue()+usage.get(token));
        }
        return response;
    }

    private List<Map<String,Object>> repairBindings(ChatModel model,String query,Map<String,Map<String,Object>> candidates,
            Map<String,Object> context,List<Map<String,Object>> bindings,Map<String,Object> metrics,Runnable cancellationGuard) {
        var groups = new LinkedHashMap<Map<String,Object>, List<Integer>>();
        for (int i = 0; i < bindings.size(); i++) {
            var original = bindings.get(i);
            var protocol = protocols(List.of(original),query,candidates,context).get(0);
            var error = map(protocol.get("binding_error"));
            if (!(error.get("errors") instanceof List<?> errors)) continue;
            var supplied = map(original.get("arguments"));
            String id = String.valueOf(original.get("template_id"));
            var template = candidates.get(id.toLowerCase(Locale.ROOT));
            // Local schema references cannot be moved out of their root schema safely.
            if (containsSchemaReference(parameterSchema(template))) continue;
            var contract = map(parameterSchema(template).get("properties"));
            var fields = new LinkedHashMap<String,Object>();
            var reasons = new LinkedHashMap<String,String>();
            for (Object raw : errors) {
                if (raw instanceof com.chatchat.agents.runtime.toolcall.ToolArgumentCompiler.ValidationError field
                        && Set.of("INVALID_REQUIRED_PARAMETER","REQUIRED_PARAMETER_MISSING").contains(field.errorCode())
                        && supplied.containsKey(field.field()) && contract.containsKey(field.field())) {
                    fields.put(field.field(),contract.get(field.field()));
                    reasons.put(field.field(),field.errorCode());
                }
            }
            if (fields.isEmpty()) continue;
            // Share a repair only for the same original proposal and the same failed field contract.
            var key = Map.<String,Object>of("fields",fields,"errors",reasons,"proposal",supplied,
                "asset",Objects.toString(publishedAssetId(template),""));
            groups.computeIfAbsent(key,ignored -> new ArrayList<>()).add(i);
        }
        if (groups.isEmpty()) return bindings;
        var patchSchemas = new LinkedHashMap<String,Object>();
        var diagnostics = new LinkedHashMap<String,Object>();
        var targets = new LinkedHashMap<String,List<Integer>>();
        groups.forEach((group,positions) -> {
            String key = "patch"+(targets.size()+1);
            var fields = map(group.get("fields"));
            var evidence = new LinkedHashMap<String,Object>();
            fields.keySet().forEach(field -> evidence.put(field,Map.of("type","string")));
            patchSchemas.put(key,Map.of("type","object","additionalProperties",false,"properties",Map.of(
                "parameters",Map.of("type","object","additionalProperties",false,"properties",fields),
                "parameterEvidence",Map.of("type","object","additionalProperties",false,"properties",evidence)),
                "required",List.of("parameters")));
            diagnostics.put(key,group.get("errors"));
            targets.put(key,positions);
        });
        try {
            var spec = ToolSpecification.fromJson(objectMapper.writeValueAsString(Map.of(
                "name",REPAIR_FUNCTION,"description","Correct only listed fields using evidence from the original request.",
                "parameters",Map.of("type","object","additionalProperties",false,"properties",patchSchemas))));
            String system = "Return one correction function call. Fill only evidenced failed fields, using exact quotes for inferred values. "
                + "Omit any patch you cannot support. Preserve every other value and do not select templates.";
            String user = "Original request: "+Objects.toString(query,"")+"\nFailed fields: "+objectMapper.writeValueAsString(diagnostics);
            metrics.put("selectionFieldRepairCalls",1);
            metrics.put("selectionFieldRepairSchemaChars",spec.toJson().length());
            var response = callModel(model,system,user,spec,metrics,cancellationGuard);
            if (response == null || response.aiMessage() == null || !response.aiMessage().hasToolExecutionRequests()
                    || response.finishReason() == FinishReason.LENGTH || response.finishReason() == FinishReason.CONTENT_FILTER)
                return bindings;
            var calls = response.aiMessage().toolExecutionRequests();
            if (calls.size()!=1 || !REPAIR_FUNCTION.equals(calls.get(0).name())) return bindings;
            Map<String,Object> patches = objectMapper.readValue(calls.get(0).arguments(),new TypeReference<>(){});
            if (patches == null || !targets.keySet().containsAll(patches.keySet())) return bindings;
            var result = new ArrayList<>(bindings);
            patches.forEach((key,raw) -> {
                var patch = map(raw);
                if (!Set.of("parameters","parameterEvidence").containsAll(patch.keySet())) return;
                var values = map(patch.get("parameters"));
                var evidence = map(patch.get("parameterEvidence"));
                var fields = map(diagnostics.get(key)).keySet();
                if (values.isEmpty() || !fields.containsAll(values.keySet()) || !values.keySet().containsAll(evidence.keySet())) return;
                for (int position : targets.get(key)) {
                    var original = bindings.get(position);
                    var revised = new LinkedHashMap<>(original);
                    var args = map(original.get("arguments"));
                    args.putAll(values);
                    var quotes = map(original.get("parameter_evidence"));
                    values.keySet().forEach(quotes::remove);
                    quotes.putAll(evidence);
                    revised.put("arguments",args);
                    revised.put("parameter_evidence",quotes);
                    var checked = protocols(List.of(revised),query,candidates,context).get(0);
                    // A default or dropped override is not a successful correction of a proposed field.
                    if (!checked.containsKey("binding_error") && map(checked.get("arguments")).keySet().containsAll(values.keySet()))
                        result.set(position,revised);
                }
            });
            return result;
        } catch (AgentBudgetExceededException | CancellationException failure) {
            throw failure;
        } catch (Exception failure) {
            metrics.put("selectionFieldRepairFailure",ModelFailureCategory.classify(failure).name());
            return bindings;
        }
    }

    private boolean containsSchemaReference(Object value) {
        if (value instanceof Map<?,?> map) return map.containsKey("$ref") || map.containsKey("$dynamicRef")
            || map.values().stream().anyMatch(this::containsSchemaReference);
        return value instanceof Collection<?> list && list.stream().anyMatch(this::containsSchemaReference);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> protocols(Object value,
                                                String userQuery,
                                                Map<String, Map<String, Object>> selected,
                                                Map<String, Object> selectionContext) {
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
            if (Boolean.TRUE.equals(binding.get("invalid_parameter_shape"))) {
                result.add(blockedPublishedBinding(authoritativeId,"INVALID_PARAMETER_SHAPE"));
                continue;
            }
            Map<String, Object> schemaProperties = map(parameterSchema(template).get("properties"));
            Map<String, Object> proposed = map(binding.get("arguments"));
            Map<String, Object> supportingQuotes = map(binding.get("parameter_evidence"));
            Map<String, Object> audited = new LinkedHashMap<>();
            List<String> unresolved = new ArrayList<>();
            List<String> invalidReferences = new ArrayList<>();
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
            map(binding.get("parameter_bindings")).forEach((name, reference) -> {
                if (!schemaProperties.containsKey(name) || proposed.containsKey(name)) {
                    unresolved.add(name);
                    invalidReferences.add(name);
                    audited.remove(name);
                    return;
                }
                TemplateRecordsetBindings.Rows rows = TemplateRecordsetBindings.resolve(selectionContext, reference);
                if (rows == null) { unresolved.add(name); invalidReferences.add(name); return; }
                audited.put(name, Map.of("value",rows.value(),"source","tool_result",
                    "evidence",Map.of("step_id",rows.stepId(),"output_path",rows.path())));
            });
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
            if(!invalidReferences.isEmpty())protocol.put("binding_error",Map.of(
                "code","INVALID_TOOL_RESULT_REFERENCE","fields",List.copyOf(invalidReferences)));
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
