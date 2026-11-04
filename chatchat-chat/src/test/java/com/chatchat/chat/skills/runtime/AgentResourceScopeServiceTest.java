package com.chatchat.chat.skills.runtime;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.retrieval.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentResourceScopeServiceTest {
    final SkillCatalogService agents=mock(SkillCatalogService.class);
    final SkillExecutionScopePort docs=mock(SkillExecutionScopePort.class);
    final ResourceAuthorizationPort authorization=mock(ResourceAuthorizationPort.class);
    final SkillDefinition agent=mock(SkillDefinition.class);
    final AgentResourceScopeService service=new AgentResourceScopeService(agents,docs,authorization);
    AgentResourceScopeServiceTest() {
        when(agents.resolve("a")).thenReturn(agent);when(agent.id()).thenReturn("a");
        when(docs.resolve(eq("t"),eq("u"),eq("a"),anyList(),anyList())).thenReturn(new SkillExecutionScopePort.EffectiveScope(List.of("doc"),List.of(),List.of(),true,true));
    }
    Map<String,Object> policy(String strategy,String... ids) {return Map.of(AgentResourcePolicy.KEY,Map.of("selectionStrategy",strategy,
        "resourcePermissions",Arrays.stream(ids).map(id->Map.of("resourceId",id,"enabled",true,"access","read")).toList()));}
    @Test void legacyDocumentsDoNotImplicitlyEnableNewsOrMarket() {
        when(agent.workflowConfig()).thenReturn(Map.of());
        when(authorization.explicitlyAllowedIdsForAgent(anyString(),anyString(),anyString(),anySet(),anySet(),anyString())).thenReturn(Set.of("financial_news","financial_market"));
        assertThat(service.resolve("t","u","a",false)).containsExactly("knowledge_documents");
    }
    @Test void userAgentAndExplicitTaskChoiceMustAllPermitTheResource() {
        when(agent.workflowConfig()).thenReturn(policy("explicit_request","knowledge_documents","financial_news","financial_market"));
        when(authorization.explicitlyAllowedIdsForAgent(anyString(),anyString(),anyString(),anySet(),anySet(),anyString())).thenReturn(Set.of("financial_news"));
        assertThat(service.resolve("t","u","a",false)).isEmpty();
        assertThat(service.resolve("t","u","a",true)).containsExactlyInAnyOrder("knowledge_documents","financial_news");
        when(authorization.explicitlyAllowedIdsForAgent(anyString(),anyString(),anyString(),anySet(),anySet(),anyString())).thenReturn(Set.of());
        assertThat(service.resolve("t","u","a",true)).containsExactly("knowledge_documents");
    }
    @Test void removingAgentGrantFailsClosedEvenWithUserResourceGrants() {
        when(docs.resolve(eq("t"),eq("u"),eq("a"),anyList(),anyList())).thenReturn(SkillExecutionScopePort.EffectiveScope.denied(List.of()));
        assertThatThrownBy(()->service.resolve("t","u","a",true)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(authorization);
    }
    @Test void malformedPoliciesCannotBecomeReadWriteOrPhysicalIndexAccess() {
        assertThatThrownBy(()->AgentResourcePolicy.read(policy("always","financial_news"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AgentResourcePolicy.read(policy("task_driven","runtime-news-*"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AgentResourcePolicy.read(Map.of(AgentResourcePolicy.KEY,Map.of("index","*","resourcePermissions",List.of())))).isInstanceOf(IllegalArgumentException.class);
        assertThat(AgentResourcePolicy.read(policy("task_driven")).enabled()).isEmpty();
    }
}
