package com.chatchat.mcpserver.template.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.workflow.AbstractStagedExecutionWorkflow;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves and validates request parameters for any registered execution template. */
@Component
@Slf4j
public class TemplateParameterWorkflow extends AbstractStagedExecutionWorkflow<
    TemplateParameterWorkflow.Request, TemplateParameterWorkflow.Analysis,
    TemplateParameterWorkflow.Plan, TemplateParameterWorkflow.Binding, TemplateParameterWorkflow.Resolution> {

    public static final String WORKFLOW_ID = "resolve.template-parameters.v1";

    private final TemplateParameterValidator validator;
    private final ObjectMapper objectMapper;

    public TemplateParameterWorkflow(TemplateParameterValidator validator, ObjectMapper objectMapper) {
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override public String workflowId() { return WORKFLOW_ID; }

    @Override
    protected void validateInput(Request input, KernelDataScope scope) {
        if (input == null) throw new IllegalArgumentException("Template parameter request is required");
        if (input.templateId() == null || input.templateId().isBlank()) {
            throw new IllegalArgumentException("Template parameter request requires templateId");
        }
    }

    @Override
    protected Analysis analyze(Request input, KernelDataScope scope) {
        Map<String, Object> schema = readSchema(input.schemaJson());
        Map<String, Object> properties = objectMap(schema.get("properties"));
        List<String> required = strings(schema.get("required"));
        String mode = input.schemaJson()==null||input.schemaJson().isBlank() ? "SCHEMALESS_PASSTHROUGH" : "DECLARED_SCHEMA";
        return new Analysis(input, mode, List.copyOf(properties.keySet()), required, properties);
    }

    @Override
    protected Plan plan(Request input, Analysis analysis, KernelDataScope scope) {
        List<String> steps = "SCHEMALESS_PASSTHROUGH".equals(analysis.mode())
            ? List.of("NORMALIZE_REQUEST", "PRESERVE_EXPLICIT_PARAMETERS", "VERIFY_BINDING", "ASSEMBLE_BINDING")
            : List.of("NORMALIZE_REQUEST", "COLLECT_EXPLICIT_PARAMETERS", "COLLECT_REQUEST_FIELDS",
                "APPLY_DECLARED_DEFAULTS", "COERCE_DECLARED_TYPES", "VALIDATE_REQUIRED_PARAMETERS",
                "ASSEMBLE_BINDING");
        return new Plan(analysis.mode(), steps);
    }

    @Override
    protected Binding executePlan(Request input, Analysis analysis, Plan plan,
                                              KernelDataScope scope) {
        if ("SCHEMALESS_PASSTHROUGH".equals(analysis.mode())) {
            Map<String, Object> passthrough = input.explicitParameters().isEmpty()
                ? input.requestValues()
                : input.explicitParameters();
            return new Binding(new LinkedHashMap<>(passthrough),List.of());
        }
        var compiled=validator.compileDeclared(input.templateId(),input.schemaJson(),input.explicitParameters(),input.requestValues());
        return new Binding(compiled.parameters(),compiled.repairs());
    }

    @Override
    protected void verify(Request input, Analysis analysis, Plan plan,
                          Binding execution, KernelDataScope scope) {
        if (execution == null) throw new IllegalStateException("Template parameter resolution produced no binding");
        List<String> missing = analysis.required().stream()
            .filter(name -> !execution.parameters().containsKey(name))
            .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Template parameter resolution left required parameters unresolved: " + missing);
        }
    }

    @Override
    protected Resolution assemble(Request input, Analysis analysis, Plan plan,
                                  Binding execution, KernelDataScope scope) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String name : execution.parameters().keySet()) {
            if(execution.repairs().stream().anyMatch(repair->name.equals(repair.field()) && repair.repairCode().contains("DEFAULT")))
                sources.put(name,"SCHEMA_DEFAULT");
            else if (input.explicitParameters().containsKey(name)) sources.put(name, "EXPLICIT_PARAMETERS");
            else if (input.requestValues().containsKey(name)) sources.put(name, "REQUEST_FIELD");
            else sources.put(name, "SCHEMA_DEFAULT");
        }
        Resolution resolution = new Resolution(input.templateId(), execution.parameters(), sources,
            analysis.declared(), analysis.required(),
            new Trace(WORKFLOW_ID, plan.mode(), plan.steps(), true,execution.repairs().stream()
                .map(repair->Map.of("field",repair.field(),"repairCode",repair.repairCode())).toList()));
        log.debug("Template parameters resolved templateId={} mode={} parameterCount={} parameterSources={}",
            input.templateId(), plan.mode(), execution.parameters().size(), sources);
        return resolution;
    }

    private Map<String, Object> readSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(schemaJson, new TypeReference<>() { });
        } catch (Exception ex) {
            throw new IllegalArgumentException("Template parameterSchema is invalid", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Object item : iterable) {
            if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item).trim());
        }
        return List.copyOf(result);
    }

    private boolean blank(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    public record Request(String templateId, String schemaJson,
                          Map<String, Object> explicitParameters,
                          Map<String, Object> requestValues) {
        public Request {
            explicitParameters = immutable(explicitParameters);
            requestValues = immutable(requestValues);
        }

        private static Map<String, Object> immutable(Map<String, Object> source) {
            return source == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }

    public record Analysis(Request request, String mode, List<String> declared,
                           List<String> required, Map<String, Object> properties) { }

    public record Plan(String mode, List<String> steps) {
        public Plan { steps = steps == null ? List.of() : List.copyOf(steps); }
    }

    public record Resolution(String templateId, Map<String, Object> parameters,
                             Map<String, String> parameterSources, List<String> declared,
                             List<String> required, Trace trace) {
        public Resolution {
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            parameterSources = Collections.unmodifiableMap(new LinkedHashMap<>(parameterSources));
            declared = List.copyOf(declared);
            required = List.copyOf(required);
        }

        public Map<String, Object> traceSummary() {
            return Map.of(
                "workflowId", trace.workflowId(),
                "mode", trace.mode(),
                "steps", trace.steps(),
                "verified", trace.verified(),
                "parameterCount", parameters.size(),
                "parameterRepairs", trace.parameterRepairs(),
                "scopeStatus",parameterSources.containsValue("SCHEMA_DEFAULT")?"REQUIRES_SCOPE_VERIFICATION":"EXPLICIT_PARAMETERS",
                "parameterSources", parameterSources
            );
        }
    }

    public record Binding(Map<String,Object> parameters,List<com.chatchat.agents.runtime.toolcall.ToolArgumentCompiler.Repair> repairs) { }

    public record Trace(String workflowId, String mode, List<String> steps, boolean verified,List<Map<String,String>> parameterRepairs) {
        public Trace(String workflowId,String mode,List<String> steps,boolean verified){this(workflowId,mode,steps,verified,List.of());}
        public Trace { steps = List.copyOf(steps); }
    }
}
