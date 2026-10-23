package com.chatchat.api.service;

import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.chat.skills.model.SkillToolConfig;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.tool.TemplateToolNames;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentTemplateToolPolicyTest {
    @Test void publishedAgentNeedsExplicitBindingAndCurrentUserGrantEvenForAdministrator() {
        var catalog = mock(SkillCatalogService.class); var identities = mock(EnterpriseAdminService.class);
        var permissions = mock(ResourceAuthorizationPort.class); var policy = new AgentTemplateToolPolicy(catalog, identities, permissions);
        var user = mock(EnterpriseAdminService.UserView.class); when(user.tenantId()).thenReturn("tenant"); when(user.status()).thenReturn("enabled");
        when(identities.getUserView("user")).thenReturn(user); when(identities.hasAllAgentAccess("user")).thenReturn(true);
        var agent = mock(SkillDefinition.class); when(agent.id()).thenReturn("agent"); when(agent.marketStatus()).thenReturn("published"); when(catalog.resolve("agent")).thenReturn(agent);
        String name = "tpl_0123456789abcdef0123456789abcdef", local = TemplateToolNames.local(name);
        when(permissions.allowedIdsForAgent(eq("MCP_TOOL"), eq("tenant"), eq("user"), anySet(), anySet(), eq("agent"))).thenReturn(Set.of(local));
        assertTrue(policy.allowed("tenant", "user", "agent", Set.of(name)).isEmpty());
        when(agent.boundMcpToolNames()).thenReturn(List.of(local)); assertEquals(Set.of(name), policy.allowed("tenant", "user", "agent", Set.of(name)));
        when(agent.toolConfigs()).thenReturn(List.of(new SkillToolConfig(local, null, null, null, List.of(), null, 1, false)));
        assertTrue(policy.allowed("tenant", "user", "agent", Set.of(name)).isEmpty());
        when(agent.toolConfigs()).thenReturn(List.of()); when(permissions.allowedIdsForAgent(anyString(), anyString(), anyString(), anySet(), anySet(), anyString())).thenReturn(Set.of());
        assertTrue(policy.allowed("tenant", "user", "agent", Set.of(name)).isEmpty());
        when(agent.marketStatus()).thenReturn("draft"); assertTrue(policy.allowed("tenant", "user", "agent", Set.of(name)).isEmpty());
        assertTrue(policy.allowed("otherTenant", "user", "agent", Set.of(name)).isEmpty());
    }
}
