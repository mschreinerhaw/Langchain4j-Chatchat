package com.chatchat.mcpserver.authorization;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void exposesRoleMembersAndAppliesRolePermissionAsTemplateCeiling() throws Exception {
        String permissions = """
            [{
              "tenantId":"tenant-1",
              "targetType":"role",
              "targetId":"role-1",
              "localToolName":"customer_profile_query",
              "effect":"allow",
              "enabled":true
            }]
            """;
        McpAuthorizationService service = service(snapshot(permissions));

        assertThat(service.roleMembers("role-1"))
            .extracting(McpAuthorizationService.UserView::username)
            .containsExactly("user1");
        assertThat(service.roleAllows("role-1", "tenant-1", "customer_profile_query", null)).isTrue();
        assertThat(service.roleAllows("role-1", "tenant-1", "unapproved_query", null)).isFalse();
    }

    @Test
    void authorizesParentInvocationAgainstServerInjectedChildToolIdentity() throws Exception {
        String permissions = """
            [{
              "tenantId":"tenant-1",
              "targetType":"role",
              "targetId":"role-1",
              "localToolName":"customer_service_template_query",
              "effect":"allow",
              "enabled":true
            }]
            """;
        McpAuthorizationService service = service(snapshot(permissions));

        McpAuthorizationService.AuthorizationDecision decision = service.authorize(
            "api_template_query",
            Map.of("userId", "user-1", "tenantId", "tenant-1",
                "_templateQueryChildToolName", "customer_service_template_query")
        );

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void usesValidatedRolesForwardedByAuthenticatedApiWhenUserMembershipSnapshotIsStale() throws Exception {
        String permissions = """
            [{
              "tenantId":"tenant-1",
              "targetType":"role",
              "targetId":"role-1",
              "localToolName":"customer_service_template_query",
              "effect":"allow",
              "enabled":true
            }]
            """;
        McpAuthorizationService service = service(snapshotWithoutUserRoles(permissions), transportRoleRepository());
        McpInvocationContext.Context context = new McpInvocationContext.Context(
            "api", "127.0.0.1", "junit", "request-1", "chatchat-api",
            "user-1", "user1", "tenant-1", "role-1", null, null, "trace-1",
            null, null, "read", null
        );

        McpAuthorizationService.AuthorizationDecision decision;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context)) {
            decision = service.authorize(
                "api_template_query",
                Map.of("_templateQueryChildToolName", "customer_service_template_query")
            );
        }

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void resolvesAuthenticatedTransportRoleByRoleName() throws Exception {
        McpAuthorizationService service = service(snapshotWithoutUserRoles("[]"), transportRoleRepository());
        McpInvocationContext.Context context = new McpInvocationContext.Context(
            "api", "127.0.0.1", "junit", "request-1", "chatchat-api",
            "user-1", "user1", "tenant-1", "User", null, null, "trace-1",
            null, null, "read", null
        );

        McpAuthorizationService.CallerAuthorizationContext caller;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context)) {
            caller = service.currentCallerContext();
        }

        assertThat(caller.roleIds()).contains("role-1");
    }

    @Test
    void resolvesDatabaseValidatedRoleWhenTransportClientIdIsUnavailable() throws Exception {
        McpAuthorizationService service = service(snapshotWithoutUserRoles("[]"), transportRoleRepository());
        McpInvocationContext.Context context = new McpInvocationContext.Context(
            "api", "127.0.0.1", "junit", "request-1", null,
            "user-1", "user1", "tenant-1", "role-1", null, null, "trace-1",
            null, null, "read", null
        );

        McpAuthorizationService.CallerAuthorizationContext caller;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context)) {
            caller = service.currentCallerContext();
        }

        assertThat(caller.roleIds()).containsExactly("role-1");
    }

    @Test
    void resolvesDatabaseValidatedRoleFromArgumentsAfterTransportThreadContextIsLost() throws Exception {
        McpAuthorizationService service = service(snapshotWithoutUserRoles("[]"), transportRoleRepository());

        McpAuthorizationService.CallerAuthorizationContext caller = service.currentCallerContext(Map.of(
            "tenantId", "tenant-1",
            "userId", "user-1",
            "username", "user1",
            "roles", "role-1"
        ));

        assertThat(caller.tenantId()).isEqualTo("tenant-1");
        assertThat(caller.userId()).isEqualTo("user-1");
        assertThat(caller.roleIds()).containsExactly("role-1");
    }

    @Test
    void rejectsForwardedRoleThatDoesNotBelongToCallerTenant() throws Exception {
        McpAuthorizationService service = service(multiTenantBusinessAdminSnapshot());
        McpInvocationContext.Context context = new McpInvocationContext.Context(
            "api", "127.0.0.1", "junit", "request-1", "chatchat-api",
            "business-admin-a", null, "tenant-a", "role-business-b", null, null, "trace-1",
            null, null, "read", null
        );

        McpAuthorizationService.CallerAuthorizationContext caller;
        try (McpInvocationContext.Scope ignored = McpInvocationContext.open(context)) {
            caller = service.currentCallerContext();
        }

        assertThat(caller.roleIds()).doesNotContain("role-business-b");
    }

    @Test
    void exposesTenantNameWithoutUsingInternalIdAsDisplayFallback() throws Exception {
        McpAuthorizationService service = service(snapshot("[]"));

        assertThat(service.tenantName("tenant-1")).isEqualTo("示例租户");
        assertThat(service.tenantName("missing-tenant")).isNull();
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
    void businessAdministratorsAreIsolatedBySynchronizedTenantAndRole() throws Exception {
        McpAuthorizationService service = service(multiTenantBusinessAdminSnapshot());

        McpAuthorizationService.AuthorizationDecision tenantAOwnAsset = service.authorize(
            "sql_query_execute",
            Map.of(
                "userId", "business-admin-a",
                "tenantId", "tenant-a",
                "scopeExpression", "mcp:sql_datasource:execute:query@tenant=tenant-a;domain=db-a;level=read"
            )
        );
        McpAuthorizationService.AuthorizationDecision tenantAOtherAsset = service.authorize(
            "sql_query_execute",
            Map.of(
                "userId", "business-admin-a",
                "tenantId", "tenant-a",
                "scopeExpression", "mcp:sql_datasource:execute:query@tenant=tenant-a;domain=db-b;level=read"
            )
        );
        McpAuthorizationService.AuthorizationDecision tenantBOwnAsset = service.authorize(
            "sql_query_execute",
            Map.of(
                "userId", "business-admin-b",
                "tenantId", "tenant-b",
                "scopeExpression", "mcp:sql_datasource:execute:query@tenant=tenant-b;domain=db-b;level=read"
            )
        );

        assertThat(tenantAOwnAsset.allowed()).isTrue();
        assertThat(tenantAOtherAsset.allowed()).isFalse();
        assertThat(tenantBOwnAsset.allowed()).isTrue();
    }

    @Test
    void synchronizedBusinessAdministratorCannotOverrideTenant() throws Exception {
        McpAuthorizationService service = service(multiTenantBusinessAdminSnapshot());

        McpAuthorizationService.AuthorizationDecision decision = service.authorize(
            "sql_query_execute",
            Map.of(
                "userId", "business-admin-a",
                "tenantId", "tenant-b",
                "roles", "BUSINESS_ADMIN,role-business-b",
                "scopeExpression", "mcp:sql_datasource:execute:query@tenant=tenant-b;domain=db-b;level=read"
            )
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("tenant does not match");
    }

    @Test
    void concurrentBusinessAdministratorRequestsRemainTenantIsolated() throws Exception {
        McpAuthorizationService service = service(multiTenantBusinessAdminSnapshot());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> requests = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> (Callable<Boolean>) () -> {
                    boolean tenantA = index % 2 == 0;
                    String tenantId = tenantA ? "tenant-a" : "tenant-b";
                    String userId = tenantA ? "business-admin-a" : "business-admin-b";
                    String ownDomain = tenantA ? "db-a" : "db-b";
                    String otherDomain = tenantA ? "db-b" : "db-a";
                    try (McpInvocationContext.Scope ignored = McpInvocationContext.open(
                        invocationContext(userId, tenantId)
                    )) {
                        McpAuthorizationService.AuthorizationDecision own = service.authorize(
                            "sql_query_execute",
                            Map.of("scopeExpression", scope(tenantId, ownDomain))
                        );
                        McpAuthorizationService.AuthorizationDecision cross = service.authorize(
                            "sql_query_execute",
                            Map.of("scopeExpression", scope(tenantId, otherDomain))
                        );
                        return own.allowed() && !cross.allowed();
                    }
                })
                .toList();

            for (Future<Boolean> result : executor.invokeAll(requests)) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void synchronizedUserCannotInjectSuperAdminRole() throws Exception {
        McpAuthorizationService service = service(multiTenantBusinessAdminSnapshot());

        McpAuthorizationService.AuthorizationDecision decision = service.authorize(
            "web_search",
            Map.of(
                "userId", "business-admin-a",
                "tenantId", "tenant-a",
                "roleIds", "SUPER_ADMIN"
            )
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("allow list");
    }

    @Test
    void rolePermissionTenantMustMatchSynchronizedRoleTenant() throws Exception {
        McpSynchronizedRoleRepository repository = mock(McpSynchronizedRoleRepository.class);
        McpSynchronizedRole role = synchronizedRole("role-business-a", "chatchat-api");
        role.setTenantId("tenant-a");
        when(repository.findById("role-business-a")).thenReturn(Optional.of(role));
        McpAuthorizationService service = service(multiTenantBusinessAdminSnapshot(), repository);

        assertThatThrownBy(() -> service.createRolePermission(
            new McpAuthorizationService.RolePermissionRequest(
                "tenant-b",
                "role-business-a",
                null,
                "sql_query_execute",
                "mcp:sql_datasource:execute:query@tenant=tenant-b;domain=db-b;level=read",
                "allow",
                true,
                "cross tenant"
            )
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("role tenant does not match");
    }

    @Test
    void rolePermissionScopeTenantMustMatchSynchronizedRoleTenant() throws Exception {
        McpSynchronizedRoleRepository repository = mock(McpSynchronizedRoleRepository.class);
        McpSynchronizedRole role = synchronizedRole("role-business-a", "chatchat-api");
        role.setTenantId("tenant-a");
        when(repository.findById("role-business-a")).thenReturn(Optional.of(role));
        McpAuthorizationService service = service(multiTenantBusinessAdminSnapshot(), repository);

        assertThatThrownBy(() -> service.createRolePermission(
            new McpAuthorizationService.RolePermissionRequest(
                "tenant-a",
                "role-business-a",
                null,
                "sql_query_execute",
                "mcp:sql_datasource:execute:query@tenant=tenant-b;domain=db-b;level=read",
                "allow",
                true,
                "cross tenant scope"
            )
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scope tenant does not match");
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

    private McpSynchronizedRoleRepository transportRoleRepository() {
        McpSynchronizedRoleRepository repository = mock(McpSynchronizedRoleRepository.class);
        McpSynchronizedRole role = synchronizedRole("role-1", "chatchat-api");
        role.setTenantId("tenant-1");
        role.setRoleCode("USER");
        role.setRoleName("User");
        role.setStatus("enabled");
        when(repository.findById("role-1")).thenReturn(Optional.of(role));
        when(repository.findFirstByTenantIdAndRoleCodeIgnoreCase("tenant-1", "User"))
            .thenReturn(Optional.of(role));
        when(repository.findFirstByTenantIdAndRoleNameIgnoreCase("tenant-1", "User"))
            .thenReturn(Optional.of(role));
        return repository;
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
              "tenants":[{"id":"tenant-1","tenantName":"示例租户"}],
              "tools":[],
              "permissions":%s
            }
            """.formatted(permissions));
        return snapshotFrom(data);
    }

    private Object snapshotWithoutUserRoles(String permissions) throws Exception {
        JsonNode data = objectMapper.readTree("""
            {
              "users":[{"id":"user-1","tenantId":"tenant-1","username":"user1","roleIds":[]}],
              "roles":[{"id":"role-1","tenantId":"tenant-1","roleCode":"USER","roleName":"User"}],
              "tenants":[{"id":"tenant-1","tenantName":"示例租户"}],
              "tools":[],
              "permissions":%s
            }
            """.formatted(permissions));
        return snapshotFrom(data);
    }

    private Object multiTenantBusinessAdminSnapshot() throws Exception {
        JsonNode data = objectMapper.readTree("""
            {
              "users":[
                {"id":"business-admin-a","tenantId":"tenant-a","username":"business-a","roleIds":["role-business-a"]},
                {"id":"business-admin-b","tenantId":"tenant-b","username":"business-b","roleIds":["role-business-b"]}
              ],
              "roles":[
                {"id":"role-business-a","tenantId":"tenant-a","roleCode":"BUSINESS_ADMIN","roleName":"业务管理员","status":"enabled"},
                {"id":"role-business-b","tenantId":"tenant-b","roleCode":"BUSINESS_ADMIN","roleName":"业务管理员","status":"enabled"}
              ],
              "tools":[],
              "permissions":[
                {
                  "tenantId":"tenant-a",
                  "targetType":"role",
                  "targetId":"role-business-a",
                  "localToolName":"sql_query_execute",
                  "scopeExpression":"mcp:sql_datasource:execute:query@tenant=tenant-a;domain=db-a;level=read",
                  "effect":"allow",
                  "enabled":true
                },
                {
                  "tenantId":"tenant-b",
                  "targetType":"role",
                  "targetId":"role-business-b",
                  "localToolName":"sql_query_execute",
                  "scopeExpression":"mcp:sql_datasource:execute:query@tenant=tenant-b;domain=db-b;level=read",
                  "effect":"allow",
                  "enabled":true
                }
              ]
            }
            """);
        return snapshotFrom(data);
    }

    private McpInvocationContext.Context invocationContext(String userId, String tenantId) {
        return new McpInvocationContext.Context(
            "test", "127.0.0.1", "junit", "request-" + userId, "client",
            userId, null, tenantId, "SUPER_ADMIN", "workspace", "test", "trace",
            "sql_datasource", null, "read", null
        );
    }

    private String scope(String tenantId, String domain) {
        return "mcp:sql_datasource:execute:query@tenant=" + tenantId + ";domain=" + domain + ";level=read";
    }

    private Object snapshotFrom(JsonNode data) throws Exception {
        Class<?> snapshotType = Class.forName(McpAuthorizationService.class.getName() + "$Snapshot");
        Method from = snapshotType.getDeclaredMethod("from", JsonNode.class);
        from.setAccessible(true);
        return from.invoke(null, data);
    }
}
