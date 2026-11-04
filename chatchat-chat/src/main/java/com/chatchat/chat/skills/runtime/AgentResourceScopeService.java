package com.chatchat.chat.skills.runtime;

import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.common.retrieval.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public final class AgentResourceScopeService implements AgentResourceScopePort {
    private final SkillCatalogService agents;
    private final SkillExecutionScopePort documents;
    private final ResourceAuthorizationPort authorization;
    public AgentResourceScopeService(SkillCatalogService agents,SkillExecutionScopePort documents,ResourceAuthorizationPort authorization) {
        this.agents=agents;this.documents=documents;this.authorization=authorization;
    }
    @Override public Set<String> resolve(String tenant,String user,String agent,boolean approved) {
        var scope=documents.resolve(tenant,user,agent,List.of(),List.of());
        if(!scope.skillAllowed())throw new SecurityException("Agent authorization unavailable");
        var definition=agents.resolve(agent);
        if(definition==null||!agent.equals(definition.id()))throw new SecurityException("Agent is unavailable");
        var policy=AgentResourcePolicy.read(definition.workflowConfig());
        var result=new HashSet<>(authorization.explicitlyAllowedIdsForAgent(AgentResourcePolicy.GRANT_TYPE,
            tenant,user,Set.of(),Set.of(AgentResourcePolicy.NEWS,AgentResourcePolicy.MARKET),agent));
        if(scope.hasKnowledgeResources()&&!scope.documentScopeDenied())result.add(AgentResourcePolicy.KNOWLEDGE);
        result.removeIf(id->!policy.permits(id,approved));return Set.copyOf(result);
    }
}
