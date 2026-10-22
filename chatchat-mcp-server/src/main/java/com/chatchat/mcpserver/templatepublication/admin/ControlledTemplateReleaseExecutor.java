package com.chatchat.mcpserver.templatepublication.admin;
import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingService;
import com.chatchat.mcpserver.templatepublication.policy.TemplateQueryToolNamePolicy;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Retains the existing API/SQL/Python/SSH/HTTP template selection and parameter binding. */
@Component @RequiredArgsConstructor
public class ControlledTemplateReleaseExecutor implements ToolReleaseExecutor {
    private final TemplateQueryBindingService bindings;
    private final ObjectMapper json;
    private final com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher publisher;
    @Override public String kind() { return "CONTROLLED_TEMPLATE"; }
    @Override public String toolName(JsonNode value) { return TemplateQueryToolNamePolicy.toolName(request(value).domainCode()); }
    @Override public String stableIdentity(JsonNode value) {
        var r=request(value);
        return String.join("|",Objects.toString(r.domainCode(),""),Objects.toString(r.parentToolName(),""),
            Objects.toString(r.roleId(),""),Objects.toString(r.subjectType(),"ROLE"),Objects.toString(r.userId(),""));
    }
    @Override public void validate(JsonNode value) { bindings.validateDraft(request(value)); }
    @Override public JsonNode test(JsonNode value,JsonNode input) {
        java.util.Map<String,Object> arguments=json.convertValue(input,new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>(){});
        return json.valueToTree(publisher.preview(request(value),arguments));
    }
    @Override public String activate(JsonNode value,String reference) {
        var r=request(value);var enabled=new TemplateQueryBindingService.UpsertRequest(r.parentToolName(),r.roleId(),
            r.subjectType(),r.userId(),r.domainCode(),r.templateKeys(),true,null,r.chineseAlias());
        return reference==null?bindings.create(enabled).id():bindings.update(reference,enabled).id();
    }
    @Override public void offline(String reference) { bindings.setEnabled(reference,false); }
    @Override public boolean unmanagedExistingDefinition(JsonNode value) {
        var r=request(value);
        return bindings.list().stream().anyMatch(b -> b.domainCode().equals(r.domainCode()) && b.roleId().equals(r.roleId())
            && Objects.equals(b.subjectType(),Objects.toString(r.subjectType(),"ROLE")) && Objects.equals(b.userId(),r.userId()));
    }
    private TemplateQueryBindingService.UpsertRequest request(JsonNode value) {
        if(value==null || !value.isObject())throw new IllegalArgumentException("Controlled tool definition required");
        try{return json.treeToValue(value,TemplateQueryBindingService.UpsertRequest.class);}
        catch(Exception ex){throw new IllegalArgumentException("Invalid controlled tool definition",ex);}
    }
}
