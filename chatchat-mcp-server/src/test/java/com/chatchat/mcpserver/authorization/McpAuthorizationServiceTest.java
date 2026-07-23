package com.chatchat.mcpserver.authorization;

import com.chatchat.common.security.InternalCredentialProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

    private McpAuthorizationService service(Object snapshot) throws Exception {
        McpAuthorizationProperties properties = new McpAuthorizationProperties();
        properties.setEnabled(true);
        properties.setFailOpen(false);
        properties.setRequireTenantContext(true);
        McpAuthorizationService service = new McpAuthorizationService(
            properties,
            mock(InternalCredentialProperties.class),
            objectMapper,
            mock(McpSynchronizedRoleRepository.class)
        );
        Field snapshotField = McpAuthorizationService.class.getDeclaredField("snapshotRef");
        snapshotField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Object> snapshotRef = (AtomicReference<Object>) snapshotField.get(service);
        snapshotRef.set(snapshot);
        return service;
    }

    private Object snapshot(String permissions) throws Exception {
        JsonNode data = objectMapper.readTree("""
            {
              "users":[{"id":"user-1","tenantId":"tenant-1","username":"user1","roleIds":["role-1"]}],
              "roles":[{"id":"role-1","tenantId":"tenant-1","roleCode":"USER","roleName":"User"}],
              "tools":[],
              "permissions":%s
            }
            """.formatted(permissions));
        Class<?> snapshotType = Class.forName(McpAuthorizationService.class.getName() + "$Snapshot");
        Method from = snapshotType.getDeclaredMethod("from", JsonNode.class);
        from.setAccessible(true);
        return from.invoke(null, data);
    }
}
