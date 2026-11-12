package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.api.runtime.coding.CodingConfigurationSnapshot;
import com.chatchat.api.runtime.coding.knowledge.CodingInternetAccess;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingRuntimeContext;
import java.util.*;
import org.springframework.stereotype.Service;

/** Capability snapshot only. No search, Skill selection or analysis plan. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public class NativeTaskCapabilities {
    private final CodingInternetAccess internet;
    @org.springframework.beans.factory.annotation.Autowired private com.chatchat.api.runtime.coding.knowledge.CodingDocumentSearchAccess documents;
    private final NativeS3Settings settings;
    private final com.chatchat.chat.skills.release.AgentReleaseService releases;
    private final com.chatchat.common.retrieval.SkillExecutionScopePort scopes;
    private final com.chatchat.chat.skills.runtime.SkillAnalysisContextService catalogs;
    public NativeTaskCapabilities(CodingInternetAccess internet,NativeS3Settings settings,com.chatchat.chat.skills.release.AgentReleaseService releases,com.chatchat.common.retrieval.SkillExecutionScopePort scopes,com.chatchat.chat.skills.runtime.SkillAnalysisContextService catalogs){this.internet=internet;this.settings=settings;this.releases=releases;this.scopes=scopes;this.catalogs=catalogs;}
    /** The generic S3 task API selects an existing published Agent capability
     * catalog. IDs alone never grant access; resolve its normal live scope. */
    public Map<String,Object> forAgent(KernelDataScope scope,String agentId,String model,boolean requested,String objective){
        if(agentId==null||agentId.isBlank())return Map.of();
        var effective=scopes.resolve(scope.tenantId(),scope.userId(),agentId,List.of(),List.of());
        if(!effective.skillAllowed())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Agent outside current account");
        var agent=releases.resolvePublished(agentId).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Published Agent required"));
        var request=com.chatchat.chat.interaction.model.InteractionRequest.builder().tenantId(scope.tenantId()).userId(scope.userId()).skillId(agentId).query(objective).modelName(model).build();
        return capture(scope,model,Map.of("authorizationAgentId",agentId,"resourceExplorationApproved",requested,com.chatchat.agents.runtime.context.SkillAnalysisContext.ATTRIBUTE,catalogs.prepare(request,agent,effective.roles())),Map.of());
    }
    public Map<String,Object> capture(KernelDataScope scope,String model,Map<String,Object> attributes,Map<String,Object> metadata){
        if(model==null||model.isBlank())model=settings.defaultModel();
        var value=new LinkedHashMap<String,Object>(CodingConfigurationSnapshot.capture(attributes,metadata));
        value.put("model",Map.of("selectedModel",model));
        if(com.chatchat.common.runtime.coding.VisualReportMode.active(attributes)){
            value.put("reportMode","visual");value.put("reportPresentation",com.chatchat.common.runtime.coding.ReportPresentation.read(attributes));
        }
        String agent=Objects.toString(attributes.get("authorizationAgentId"),"");
        if(documents!=null){var documentPolicy=documents.snapshot(scope,agent,Boolean.TRUE.equals(attributes.get("resourceExplorationApproved")));if(!documentPolicy.isEmpty())value.put("documentSearch",documentPolicy);}
        var policy=internet.snapshot(scope,agent,model,Boolean.TRUE.equals(attributes.get("resourceExplorationApproved")));
        if(!policy.isEmpty())value.put("internetSearch",policy);
        value.put("internetAvailability",internet.availability(scope,agent,model,Boolean.TRUE.equals(attributes.get("resourceExplorationApproved")),!policy.isEmpty()));
        return CodingRuntimeContext.validate(value);
    }
}
