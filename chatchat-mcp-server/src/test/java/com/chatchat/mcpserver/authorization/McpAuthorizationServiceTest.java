package com.chatchat.mcpserver.authorization;

import com.chatchat.common.security.InternalCredentialProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpAuthorizationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deniesRoleWithoutAnyAssetAuthorization() throws Exception {
        McpAuthorizationService service = service(snapshot("[]"));

        McpAuthorizationService.AuthorizationDecision decision = service.authorize(
            "sql_asset_query",
            Map.of("userId", "user-1", "tenantId", "tenant-1")
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("no MCP asset authorization");
    }

    @Test
    void deniesCallerWhenRequiredTenantContextIsMissing() throws Exception {
        McpAuthorizationService service = service(snapshot("[]"));

        McpAuthorizationService.AuthorizationDecision decision = service.authorize(
            "sql_asset_query",
            Map.of("userId", "unknown-user")
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("tenant context is missing");
    }

    @Test
    void requiresToolAndAssetScopeToMatch() throws Exception {
        String permissions = """
            [{
              "tenantId":"tenant-1",
              "targetType":"role",
              "targetId":"role-1",
              "localToolName":"sql_asset_query",
              "scopeExpression":"mcp:sql_datasource:execute:query@tenant=tenant-1;domain=db-1;level=read",
              "effect":"allow",
              "enabled":true
            }]
            """;
        McpAuthorizationService service = service(snapshot(permissions));

        McpAuthorizationService.AuthorizationDecision allowed = service.authorize(
            "sql_asset_query",
            Map.of(
                "userId", "user-1",
                "tenantId", "tenant-1",
                "scopeExpression", "mcp:sql_datasource:execute:query@tenant=tenant-1;domain=db-1;level=read"
            )
        );
        McpAuthorizationService.AuthorizationDecision otherAsset = service.authorize(
            "sql_asset_query",
            Map.of(
                "userId", "user-1",
                "tenantId", "tenant-1",
                "scopeExpression", "mcp:sql_datasource:execute:query@tenant=tenant-1;domain=db-2;level=read"
            )
        );
        McpAuthorizationService.AuthorizationDecision routedGateway = service.authorize(
            "sql_query_execute",
            Map.of(
                "userId", "user-1",
                "tenantId", "tenant-1",
                "scopeExpression", "mcp:sql_datasource:execute:query@tenant=tenant-1;domain=db-1;level=read"
            )
        );
        McpAuthorizationService.AuthorizationDecision directAssetTool = service.authorize(
            "sql_asset_query",
            Map.of("userId", "user-1", "tenantId", "tenant-1")
        );

        assertThat(allowed.allowed()).isTrue();
        assertThat(otherAsset.allowed()).isFalse();
        assertThat(routedGateway.allowed()).isTrue();
        assertThat(directAssetTool.allowed()).isTrue();
    }

    @Test
    void adminUserIdIsResolvedToWhitelistedUsername() throws Exception {
        McpAuthorizationService service = service(snapshot("[]"));

        McpAuthorizationService.AuthorizationDecision decision = service.authorize(
            "web_search",
            Map.of("userId", "user-admin-id", "tenantId", "tenant-1")
        );

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void legacyAdminUsernameStoredAsUserIdStillUsesWhitelist() throws Exception {
        McpAuthorizationService service = service(snapshot("[]"));

        McpAuthorizationService.AuthorizationDecision decision = service.authorize(
            "web_search",
            Map.of("userId", "admin", "tenantId", "tenant-1")
        );

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void requestUsernameCannotSpoofAdminForResolvedNormalUser() throws Exception {
        McpAuthorizationService service = service(snapshot("[]"));

        McpAuthorizationService.AuthorizationDecision decision = service.authorize(
            "web_search",
            Map.of(
                "userId", "user-1",
                "username", "admin",
                "tenantId", "tenant-1"
            )
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("no MCP asset authorization");
    }

    @Test
    void nestedMcpIdentityIsResolvedForAuthorization() throws Exception {
        McpAuthorizationService service = service(snapshot("[]"));

        McpAuthorizationService.AuthorizationDecision decision = service.authorize(
            "web_search",
            Map.of(
                "tenantId", "tenant-1",
                "mcpContext", Map.of("identity", Map.of("userId", "user-admin-id"))
            )
        );

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void roleSynchronizationDeletesRolesMissingFromApiSnapshot() throws Exception {
        McpSynchronizedRoleRepository repository = mock(McpSynchronizedRoleRepository.class);
        McpSynchronizedRole stale = synchronizedRole("stale-role", "chatchat-api");
        when(repository.findById("role-1")).thenReturn(Optional.empty());
        when(repository.findAll()).thenReturn(List.of(stale));
        McpAuthorizationService service = service(snapshot("[]"), repository);

        synchronizeRoles(service, snapshot("[]"));

        verify(repository).saveAll(anyList());
        verify(repository).deleteAllInBatch(List.of(stale));
    }

    @Test
    void emptyApiSnapshotDeletesAllPreviouslySynchronizedRoles() throws Exception {
        McpSynchronizedRoleRepository repository = mock(McpSynchronizedRoleRepository.class);
        McpSynchronizedRole stale = synchronizedRole("stale-role", "chatchat-api");
        when(repository.findAll()).thenReturn(List.of(stale));
        McpAuthorizationService service = service(emptySnapshot(), repository);

        synchronizeRoles(service, emptySnapshot());

        verify(repository, never()).saveAll(anyList());
        verify(repository).deleteAllInBatch(List.of(stale));
    }

    private McpAuthorizationService service(Object snapshot) throws Exception {
        return service(snapshot, mock(McpSynchronizedRoleRepository.class));
    }

    private McpAuthorizationService service(
        Object snapshot,
        McpSynchronizedRoleRepository repository
    ) throws Exception {
        McpAuthorizationProperties properties = new McpAuthorizationProperties();
        properties.setEnabled(true);
        properties.setFailOpen(false);
        properties.setRequireTenantContext(true);
        McpAuthorizationService service = new McpAuthorizationService(
            properties,
            mock(InternalCredentialProperties.class),
            objectMapper,
            repository
        );
        Field snapshotField = McpAuthorizationService.class.getDeclaredField("snapshotRef");
        snapshotField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Object> snapshotRef = (AtomicReference<Object>) snapshotField.get(service);
        snapshotRef.set(snapshot);
        return service;
    }

    private void synchronizeRoles(McpAuthorizationService service, Object snapshot) throws Exception {
        Method method = McpAuthorizationService.class.getDeclaredMethod(
            "synchronizeRoles",
            snapshot.getClass()
        );
        method.setAccessible(true);
        method.invoke(service, snapshot);
    }

    private McpSynchronizedRole synchronizedRole(String id, String source) {
        McpSynchronizedRole role = new McpSynchronizedRole();
        role.setId(id);
        role.setSource(source);
        return role;
    }

    private Object emptySnapshot() throws Exception {
        JsonNode data = objectMapper.readTree("""
            {
              "users":[],
              "roles":[],
              "tools":[],
              "permissions":[]
            }
            """);
        return snapshotFrom(data);
    }

    private Object snapshot(String permissions) throws Exception {
        JsonNode data = objectMapper.readTree("""
            {
              "users":[
                {"id":"user-1","tenantId":"tenant-1","username":"user1","roleIds":["role-1"]},
                {"id":"user-admin-id","tenantId":"tenant-1","tenantNo":100000,"username":"admin","roleIds":[]}
              ],
              "roles":[{"id":"role-1","tenantId":"tenant-1","roleCode":"USER","roleName":"User"}],
              "tools":[],
              "permissions":%s
            }
            """.formatted(permissions));
        return snapshotFrom(data);
    }

    private Object snapshotFrom(JsonNode data) throws Exception {
        Class<?> snapshotType = Class.forName(McpAuthorizationService.class.getName() + "$Snapshot");
        Method from = snapshotType.getDeclaredMethod("from", JsonNode.class);
        from.setAccessible(true);
        return from.invoke(null, data);
    }
}
