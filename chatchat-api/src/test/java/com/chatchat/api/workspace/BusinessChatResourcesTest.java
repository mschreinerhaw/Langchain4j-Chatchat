package com.chatchat.api.workspace;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.domain.application.DomainSkillService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.chat.task.core.AgentTaskSubmitRequest;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.enterprise.entity.security.SkillResourceScope;
import com.chatchat.enterprise.repository.security.SkillResourceScopeRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.knowledgebase.search.service.SearchService;
import com.chatchat.knowledgebase.search.model.SearchResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessChatResourcesTest {
    @Test
    void requiresAgentAndRoleGrantsBeforeRetrievingSelectedKnowledge() {
        var access = mock(ObjectAccessService.class);
        var workspaces = mock(WorkspaceService.class);
        var authorization = mock(ResourceAuthorizationPort.class);
        var agents = mock(SkillCatalogService.class);
        var bindings = mock(SkillResourceScopeRepository.class);
        var search = mock(SearchService.class, RETURNS_DEEP_STUBS);
        var scope = mock(SkillExecutionScopePort.class);
        var resources = new BusinessChatResources(access, workspaces, mock(EnterpriseAdminService.class), search,
            authorization, mock(DomainSkillService.class), agents, bindings, scope);
        var servlet = new MockHttpServletRequest();
        servlet.addHeader(WorkspaceOriginService.HEADER, "business");
        var request = new AgentTaskSubmitRequest();
        request.setQuery("核对订单");
        request.setToolInput(Map.of("businessReferences", Map.of("knowledgeBases", List.of("operations"))));
        when(access.tenant(servlet)).thenReturn("tenant");
        when(access.user(servlet)).thenReturn("user");
        when(workspaces.permissions(servlet)).thenReturn(Set.of("workspace:business:knowledge"));
        when(agents.isPublished("agent")).thenReturn(true);
        when(agents.resolve("agent")).thenReturn(mock(SkillDefinition.class));
        var binding = new SkillResourceScope();
        binding.setEnabled(true);
        binding.setResourceType("KNOWLEDGE_BASE");
        binding.setResourceId("operations");
        when(bindings.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc("tenant", "agent"))
            .thenReturn(List.of(binding));
        assertThatThrownBy(() -> resources.apply(servlet, request, "agent")).isInstanceOf(IllegalArgumentException.class);
        assertThat(request.getQuery()).isEqualTo("核对订单");
        verifyNoInteractions(search);

        when(authorization.explicitlyAllowedIdsForAgent(ResourceAuthorizationPort.KNOWLEDGE_BASE, "tenant", "user",
            Set.of(), Set.of("operations"), "agent")).thenReturn(Set.of("operations"));
        when(scope.resolve("tenant", "user", "agent", List.of(), List.of())).thenReturn(
            new SkillExecutionScopePort.EffectiveScope(List.of("allowed-doc"), List.of(), List.of("role"), true, true));
        var policy = new SearchPermissionContext("tenant", "user", List.of("role"), "agent");
        var allowed = mock(SearchResult.class);
        when(allowed.docId()).thenReturn("allowed-doc");
        when(allowed.title()).thenReturn("订单资料");
        when(allowed.summary()).thenReturn("订单口径");
        var forbidden = mock(SearchResult.class);
        when(forbidden.docId()).thenReturn("unbound-doc");
        when(search.search("核对订单", "operations", null, null, "allowed-doc", 1, 2, policy).results())
            .thenReturn(List.of(allowed, forbidden));
        resources.apply(servlet, request, "agent");
        assertThat(request.getQuery()).contains("operations", "订单口径").doesNotContain("unbound-doc");
    }
}
