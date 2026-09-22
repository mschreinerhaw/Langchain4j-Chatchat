package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.common.tool.ToolWorkflowContractCatalog;
import com.chatchat.common.tool.ToolWorkflowContractSnapshot;
import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.entity.mcp.McpToolAsset;
import com.chatchat.enterprise.entity.mcp.McpToolPermission;
import com.chatchat.enterprise.repository.identity.SysRoleRepository;
import com.chatchat.enterprise.repository.identity.SysTenantRepository;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.enterprise.repository.identity.SysUserRoleRepository;
import com.chatchat.enterprise.repository.mcp.McpToolAssetRepository;
import com.chatchat.enterprise.repository.mcp.McpToolPermissionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseMcpToolCandidateRetrieverTest {
    @Test
    void searchesOnlyDatabaseAuthorizedOnlineToolsAndRejectsUnexpectedIndexIds() {
        McpToolAssetRepository tools = mock(McpToolAssetRepository.class);
        McpToolPermissionRepository permissions = mock(McpToolPermissionRepository.class);
        SysUserRepository users = mock(SysUserRepository.class);
        SysUserRoleRepository userRoles = mock(SysUserRoleRepository.class);
        SysRoleRepository roles = mock(SysRoleRepository.class);
        SysTenantRepository tenants = mock(SysTenantRepository.class);
        McpToolSemanticIndex index = mock(McpToolSemanticIndex.class);
        McpToolAsset allowed = tool("allowed", true);
        McpToolAsset disabled = tool("disabled", false);
        McpToolAsset forbidden = tool("forbidden", true);
        when(tools.findAllByOrderByLocalToolNameAsc()).thenReturn(List.of(allowed, disabled, forbidden));
        when(tools.findByLocalToolName("allowed")).thenReturn(Optional.of(allowed));
        SysUser user = new SysUser();
        user.setId("user-1");
        user.setTenantId("tenant-1");
        user.setStatus("enabled");
        user.setUsername("alice");
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        when(userRoles.findByUserId("user-1")).thenReturn(List.of());
        when(roles.findByTenantIdOrderByRoleNameAsc("tenant-1")).thenReturn(List.of());
        when(permissions.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
            eq("tenant-1"), eq("USER"), eq("user-1"))).thenReturn(List.of(grant("allowed"), grant("disabled")));
        when(index.rank(any(), any(), eq(3))).thenReturn(List.of("forbidden", "allowed"));
        DatabaseMcpToolCandidateRetriever retriever = new DatabaseMcpToolCandidateRetriever(
            tools, permissions, users, userRoles, roles, tenants, index,
            mock(ToolWorkflowContractCatalog.class));

        McpToolCandidateRetriever.Selection result = retriever.retrieve(
            InteractionRequest.builder().tenantId("tenant-1").userId("user-1")
                .query("customer assets").build(),
            List.of("allowed", "disabled", "forbidden"), 3);

        assertThat(result.managedNames()).containsExactlyInAnyOrder("allowed", "disabled", "forbidden");
        assertThat(result.allowedNames()).containsExactly("allowed");
        assertThat(result.rankedNames()).containsExactly("allowed");
    }

    @Test
    void rejectsCrossTenantIdentityBeforeSemanticSearch() {
        McpToolAssetRepository tools = mock(McpToolAssetRepository.class);
        McpToolPermissionRepository permissions = mock(McpToolPermissionRepository.class);
        SysUserRepository users = mock(SysUserRepository.class);
        McpToolSemanticIndex index = mock(McpToolSemanticIndex.class);
        when(tools.findAllByOrderByLocalToolNameAsc()).thenReturn(List.of(tool("allowed", true)));
        SysUser user = new SysUser();
        user.setTenantId("other-tenant");
        user.setStatus("enabled");
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        DatabaseMcpToolCandidateRetriever retriever = new DatabaseMcpToolCandidateRetriever(
            tools, permissions, users, mock(SysUserRoleRepository.class), mock(SysRoleRepository.class),
            mock(SysTenantRepository.class), index, mock(ToolWorkflowContractCatalog.class));

        assertThat(retriever.retrieve(InteractionRequest.builder().tenantId("tenant-1")
            .userId("user-1").query("assets").build(), List.of("allowed"), 3).allowedNames()).isEmpty();
        verifyNoInteractions(index);
    }

    @Test
    void scopedGrantForAnotherToolDoesNotAuthorizeCandidate() {
        McpToolAssetRepository tools = mock(McpToolAssetRepository.class);
        McpToolPermissionRepository permissions = mock(McpToolPermissionRepository.class);
        SysUserRepository users = mock(SysUserRepository.class);
        McpToolSemanticIndex index = mock(McpToolSemanticIndex.class);
        when(tools.findAllByOrderByLocalToolNameAsc()).thenReturn(List.of(tool("restricted", true)));
        SysUser user = new SysUser();
        user.setId("user-1"); user.setTenantId("tenant-1"); user.setStatus("enabled");
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        McpToolPermission unrelated = grant("other-tool");
        unrelated.setScopeExpression("region=west");
        when(permissions.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
            "tenant-1", "USER", "user-1")).thenReturn(List.of(unrelated));
        DatabaseMcpToolCandidateRetriever retriever = new DatabaseMcpToolCandidateRetriever(
            tools, permissions, users, mock(SysUserRoleRepository.class), mock(SysRoleRepository.class),
            mock(SysTenantRepository.class), index, mock(ToolWorkflowContractCatalog.class));

        McpToolCandidateRetriever.Selection result = retriever.retrieve(InteractionRequest.builder()
            .tenantId("tenant-1").userId("user-1").query("restricted").build(),
            List.of("restricted"), 3);

        assertThat(result.allowedNames()).isEmpty();
        assertThat(result.rankedNames()).isEmpty();
    }

    @Test
    void removesToolDisabledAfterInitialDatabaseScopeBeforeReturningIndexHit() {
        McpToolAssetRepository tools = mock(McpToolAssetRepository.class);
        McpToolPermissionRepository permissions = mock(McpToolPermissionRepository.class);
        SysUserRepository users = mock(SysUserRepository.class);
        McpToolSemanticIndex index = mock(McpToolSemanticIndex.class);
        McpToolAsset initiallyOnline = tool("report_generation", true);
        McpToolAsset nowDisabled = tool("report_generation", false);
        when(tools.findAllByOrderByLocalToolNameAsc()).thenReturn(List.of(initiallyOnline));
        when(tools.findByLocalToolName("report_generation")).thenReturn(Optional.of(nowDisabled));
        SysUser user = new SysUser();
        user.setId("user-1"); user.setTenantId("tenant-1"); user.setStatus("enabled");
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        when(permissions.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
            "tenant-1", "USER", "user-1")).thenReturn(List.of(grant("report_generation")));
        DatabaseMcpToolCandidateRetriever retriever = new DatabaseMcpToolCandidateRetriever(
            tools, permissions, users, mock(SysUserRoleRepository.class), mock(SysRoleRepository.class),
            mock(SysTenantRepository.class), index, mock(ToolWorkflowContractCatalog.class));

        McpToolCandidateRetriever.Selection result = retriever.retrieve(InteractionRequest.builder()
            .tenantId("tenant-1").userId("user-1").query("generate report").build(),
            List.of("report_generation"), 3);

        assertThat(result.allowedNames()).isEmpty();
        assertThat(result.rankedNames()).isEmpty();
        verifyNoInteractions(index);
    }

    @Test
    void expandsPublishedDependenciesOnlyWithinAuthorizedTools() {
        McpToolAssetRepository tools = mock(McpToolAssetRepository.class);
        McpToolPermissionRepository permissions = mock(McpToolPermissionRepository.class);
        SysUserRepository users = mock(SysUserRepository.class);
        McpToolSemanticIndex index = mock(McpToolSemanticIndex.class);
        ToolWorkflowContractCatalog contracts = mock(ToolWorkflowContractCatalog.class);
        McpToolAsset asset = tool("asset_query", true);
        McpToolAsset analysis = tool("profit_analysis", true);
        McpToolAsset forbidden = tool("report_generation", true);
        when(tools.findAllByOrderByLocalToolNameAsc()).thenReturn(List.of(asset, analysis, forbidden));
        when(tools.findByLocalToolName("asset_query")).thenReturn(Optional.of(asset));
        when(tools.findByLocalToolName("profit_analysis")).thenReturn(Optional.of(analysis));
        SysUser user = new SysUser();
        user.setId("user-1"); user.setTenantId("tenant-1"); user.setStatus("enabled"); user.setUsername("alice");
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        when(permissions.findByTenantIdAndTargetTypeAndTargetIdAndEnabledTrueOrderByUpdatedAtDesc(
            "tenant-1", "USER", "user-1")).thenReturn(List.of(grant("asset_query"), grant("profit_analysis")));
        when(index.rank(any(), any(), eq(3))).thenReturn(List.of("profit_analysis"));
        ToolWorkflowContractSnapshot contract = mock(ToolWorkflowContractSnapshot.class);
        when(contract.extensions()).thenReturn(java.util.Map.of(
            "dependsOnTools", List.of("asset_query", "report_generation")));
        when(contracts.findActive(null, "profit_analysis", "profit_analysis"))
            .thenReturn(Optional.of(contract));
        DatabaseMcpToolCandidateRetriever retriever = new DatabaseMcpToolCandidateRetriever(
            tools, permissions, users, mock(SysUserRoleRepository.class), mock(SysRoleRepository.class),
            mock(SysTenantRepository.class), index, contracts);

        assertThat(retriever.retrieve(InteractionRequest.builder().tenantId("tenant-1")
            .userId("user-1").query("profit report").build(),
            List.of("asset_query", "profit_analysis", "report_generation"), 3).rankedNames())
            .containsExactly("asset_query", "profit_analysis");
    }

    private McpToolAsset tool(String name, boolean enabled) {
        McpToolAsset tool = new McpToolAsset();
        tool.setId(name + "-id");
        tool.setLocalToolName(name);
        tool.setRemoteToolName(name);
        tool.setEnabled(enabled);
        tool.setStatus("online");
        return tool;
    }

    private McpToolPermission grant(String name) {
        McpToolPermission permission = new McpToolPermission();
        permission.setLocalToolName(name);
        permission.setEnabled(true);
        permission.setEffect("allow");
        return permission;
    }
}
