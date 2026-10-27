package com.chatchat.mcpserver.template;

import com.chatchat.agents.runtime.toolcall.ToolArgumentCompiler;
import com.chatchat.common.knowledge.template.resolution.TemplateResolutionEvent;
import com.chatchat.common.knowledge.template.resolution.TemplateResolutionException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared deterministic compilation, without model or business inference. */
@Component
@RequiredArgsConstructor
public class TemplateParameterValidator {
    private final ObjectMapper objectMapper;
    private final ToolArgumentCompiler compiler=new ToolArgumentCompiler();

    public Map<String,Object> collect(String schemaJson,Map<String,Object> explicit,Map<String,Object> source) {
        Map<String,Object> collected=new LinkedHashMap<>(explicit==null?Map.of():explicit);
        Map<String,Object> schema=readSchema(schemaJson);
        if(source!=null && schema.get("properties") instanceof Map<?,?> fields)
            for(Object key:fields.keySet())if(key instanceof String name && !collected.containsKey(name) && source.containsKey(name))
                collected.put(name,source.get(name));
        return collected;
    }

    public Map<String,Object> validate(String templateId,String schemaJson,Map<String,Object> parameters) {
        return compileDeclared(templateId,schemaJson,parameters,Map.of()).parameters();
    }

    public Map<String,Object> validateDeclaredOnly(String templateId,String schemaJson,
            Map<String,Object> explicit,Map<String,Object> source) {
        return compileDeclared(templateId,schemaJson,explicit,source).parameters();
    }

    public ToolArgumentCompiler.CompilationResult compileDeclared(String templateId,String schemaJson,
            Map<String,Object> explicit,Map<String,Object> source) {
        // Missing-schema legacy API contracts retain passthrough. An explicitly
        // published empty schema has no declared execution parameters.
        if(schemaJson==null || schemaJson.isBlank())
            return new ToolArgumentCompiler.CompilationResult("READY",new LinkedHashMap<>(
                explicit==null||explicit.isEmpty()?source==null?Map.of():source:explicit),List.of(),List.of());
        var result=compiler.compileTemplate(collect(schemaJson,explicit,source),readSchema(schemaJson));
        if(!result.valid()) {
            List<String> missing=result.validationErrors().stream()
                .filter(error->"REQUIRED_PARAMETER_MISSING".equals(error.errorCode()))
                .map(ToolArgumentCompiler.ValidationError::field).toList();
            if(missing.size()==result.validationErrors().size())
                throw new TemplateResolutionException(TemplateResolutionEvent.missingParameters(null,templateId,missing));
            throw new ParameterValidationException(result.validationErrors());
        }
        return result;
    }

    private Map<String,Object> readSchema(String schemaJson) {
        if(schemaJson==null||schemaJson.isBlank())return Map.of();
        try {return objectMapper.readValue(schemaJson,new TypeReference<>(){});}
        catch(Exception invalid){throw new IllegalArgumentException("Template parameterSchema is invalid");}
    }

    public static final class ParameterValidationException extends IllegalArgumentException {
        private final List<ToolArgumentCompiler.ValidationError> validationErrors;
        public ParameterValidationException(List<ToolArgumentCompiler.ValidationError> errors) {
            super((errors.stream().anyMatch(e->"INVALID_TEMPLATE_DEFAULT".equals(e.errorCode()))
                ?"INVALID_TEMPLATE_DEFAULT":"INVALID_TOOL_ARGUMENTS")+": "+errors.stream()
                .map(e->e.field()+" ("+e.errorCode()+")").toList());
            validationErrors=List.copyOf(errors);
        }
        public List<ToolArgumentCompiler.ValidationError> validationErrors(){return validationErrors;}
    }
}
