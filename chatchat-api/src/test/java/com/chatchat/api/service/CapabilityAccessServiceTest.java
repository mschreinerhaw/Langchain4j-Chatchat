package com.chatchat.api.service;

import com.chatchat.agents.runtime.tool.*;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.api.runtime.EnterpriseToolRuntimePolicyProvider;
import com.chatchat.api.exception.RuntimeScopeAccessDeniedException;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.chat.skills.model.SkillToolConfig;
import com.chatchat.common.kernel.KernelHealth;
import com.chatchat.common.mcp.capability.CapabilityManifest;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.enterprise.service.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class CapabilityAccessServiceTest {
    McpRuntimeAccessService mcp = mock(McpRuntimeAccessService.class);
    CapabilitySnapshotStore store = mock(CapabilitySnapshotStore.class);
    ToolRegistry tools = mock(ToolRegistry.class);
    EnterpriseToolRuntimePolicyProvider policy = mock(EnterpriseToolRuntimePolicyProvider.class);
    ResourceAuthorizationPort grants = mock(ResourceAuthorizationPort.class);
    SkillCatalogService agents = mock(SkillCatalogService.class);
    EnterpriseAdminService users = mock(EnterpriseAdminService.class);
    ToolRuntimeService executor = mock(ToolRuntimeService.class);
    CapabilityAccessService access = new CapabilityAccessService(mcp, store, tools, policy, grants, agents, users, executor);
    CapabilityAccessService.Scope scope = new CapabilityAccessService.Scope("tenant", "user", null);
    CapabilityManifest manifest(String name, String status) {
        return new CapabilityManifest(CapabilityManifest.V1, name, "v1", status,
            Map.of("serverId", "server", "toolName", name),
            Map.of("name", name, "description", "Stored data", "keywords", List.of("quotes")),
            Map.of("inputSchema", Map.of("type", "object")), Map.of(), Map.of(), Map.of());
    }
    @BeforeEach void setup() {
        when(mcp.tools(any())).thenReturn(List.of());
        when(mcp.health()).thenReturn(mock(KernelHealth.class));
        when(store.synchronize(anyList(), anySet())).thenReturn(List.of(manifest("read", "PUBLISHED"),
            manifest("denied", "PUBLISHED"), manifest("draft", "DRAFT")));
        when(tools.getToolMetadata(anyString())).thenAnswer(call -> ToolMetadata.builder()
            .id(call.getArgument(0)).agentCompatible(true).categories(List.of("mcp")).build());
        when(policy.resolve(any(), any())).thenAnswer(call -> ToolRuntimePolicy.builder()
            .allowed("read".equals(((ToolRuntimeRequest)call.getArgument(0)).getToolName())).build());
    }
    @Test void directoryFiltersBeforeCountingAndDoesNotSendSchemas() {
        var page = access.discover(scope, "quotes", 0, 20);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(CapabilityAccessService.Summary::toolName).containsExactly("read");
        verifyNoInteractions(executor);
    }
    @Test void forbiddenDetailsAndUnknownDetailsAreIndistinguishable() {
        assertThatThrownBy(() -> access.detail(scope, "denied")).hasMessageContaining("404");
        assertThatThrownBy(() -> access.detail(scope, "absent")).hasMessageContaining("404");
    }
    @Test void staleVersionIsRejectedBeforeAnyExecution() {
        assertThatThrownBy(() -> access.invoke(scope, "read", new CapabilityAccessService.Invocation("old", Map.of(), null)))
            .hasMessageContaining("409");
        verifyNoInteractions(executor);
    }
    @Test void executionUsesBoundIdentityAndExistingRuntime() {
        access.invoke(scope, "read", new CapabilityAccessService.Invocation("v1", Map.of("query", "test"), "conversation"));
        ArgumentCaptor<ToolRuntimeRequest> capture = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(executor).execute(capture.capture());
        assertThat(capture.getValue().getTenantId()).isEqualTo("tenant");
        assertThat(capture.getValue().getToolInput().getContext()).containsEntry("userId", "user");
        assertThat(capture.getValue().getAllowedTools()).containsExactly("read");
    }
    @Test void permissionRevocationIsRecheckedAtInvocation() {
        assertThat(access.detail(scope, "read")).isNotNull();
        doReturn(ToolRuntimePolicy.builder().allowed(false).build()).when(policy).resolve(any(), any());
        assertThatThrownBy(() -> access.invoke(scope, "read", new CapabilityAccessService.Invocation("v1", Map.of(), null)))
            .hasMessageContaining("404");
        verifyNoInteractions(executor);
    }
    @Test void missingDynamicChildDecisionDoesNotInheritParentPermissions() {
        doReturn(null).when(policy).resolve(any(), any());
        when(grants.allowedIdsForAgent(anyString(), anyString(), anyString(), anySet(), anySet(), isNull()))
            .thenReturn(Set.of());
        assertThat(access.discover(scope, "", 0, 20).items()).isEmpty();
    }
    @Test void unauthenticatedAndUnboundedDiscoveryAreRejected() {
        assertThatThrownBy(() -> new CapabilityAccessService.Scope("tenant", null, null))
            .isInstanceOf(RuntimeScopeAccessDeniedException.class);
        assertThatThrownBy(() -> access.discover(scope, "", 0, 1000)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }
    @Test void agentMustBeAccessibleAndBindingsNarrowTheDirectory() {
        SkillDefinition agent = mock(SkillDefinition.class);
        when(agents.resolve("agent")).thenReturn(agent);
        when(agent.id()).thenReturn("agent");
        when(agent.marketStatus()).thenReturn("published");
        var agentScope = new CapabilityAccessService.Scope("tenant", "user", "agent");
        assertThatThrownBy(() -> access.discover(agentScope, "", 0, 20))
            .isInstanceOf(RuntimeScopeAccessDeniedException.class);
        when(users.canAccessAgent("user", "agent")).thenReturn(true);
        assertThat(access.discover(agentScope, "", 0, 20).items()).isEmpty();
        when(agent.boundMcpServiceIds()).thenReturn(List.of("server"));
        assertThat(access.discover(agentScope, "", 0, 20).items()).hasSize(1);
        when(agent.boundMcpToolNames()).thenReturn(List.of("other"));
        assertThat(access.discover(agentScope, "", 0, 20).items()).isEmpty();
        when(agent.boundMcpToolNames()).thenReturn(List.of("read"));
        assertThat(access.discover(agentScope, "", 0, 20).items()).hasSize(1);
        when(agent.toolConfigs()).thenReturn(List.of(new SkillToolConfig("read", "Read", "server", "", List.of(), null, null, false)));
        assertThat(access.discover(agentScope, "", 0, 20).items()).isEmpty();
    }
}
