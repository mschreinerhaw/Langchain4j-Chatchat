package com.chatchat.mcpserver.templatepublication;

import com.chatchat.mcpserver.authorization.McpSynchronizedRole;
import com.chatchat.mcpserver.authorization.McpSynchronizedRoleRepository;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateQueryBindingServiceTest {

    @Test
    void rejectsTemplateWhoseCategoryDoesNotMatchTheFixedParentQuery() {
        TemplateQueryBindingRepository repository = mock(TemplateQueryBindingRepository.class);
        McpSynchronizedRoleRepository roles = mock(McpSynchronizedRoleRepository.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryBindingService service = new TemplateQueryBindingService(
            repository, catalog, new TemplateQueryParentCatalog(), roles, new ObjectMapper(), authorization);
        when(roles.findById("role-1")).thenReturn(Optional.of(role("role-1", "FINANCE")));
        when(repository.findByDomainCode("customer")).thenReturn(List.of());
        when(catalog.listAuthorizedForRoleAndType("role-1", TemplateAssetCatalogService.API))
            .thenReturn(List.of(asset("api_service:customer_query")));

        TemplateQueryBindingService.UpsertRequest request = new TemplateQueryBindingService.UpsertRequest(
            "api_template_query", "role-1", "ROLE", null, "customer",
            List.of("database_query:balance_query"), true, null);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match the selected parent query");
    }

    @Test
    void unionsOnlyBindingsMatchingAuthenticatedServiceTenantAndCallerRole() {
        TemplateQueryBindingRepository repository = mock(TemplateQueryBindingRepository.class);
        McpSynchronizedRoleRepository roles = mock(McpSynchronizedRoleRepository.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryBindingService service = new TemplateQueryBindingService(
            repository, catalog, new TemplateQueryParentCatalog(), roles, new ObjectMapper(), authorization);
        TemplateQueryBinding matching = binding("binding-1", "tenant-1", TemplateQueryParentCatalog.SERVICE_ID, "role-1",
            "[\"api_service:customer_query\"]");
        TemplateQueryBinding otherRole = binding("binding-2", "tenant-1", TemplateQueryParentCatalog.SERVICE_ID, "role-2",
            "[\"api_service:secret_query\"]");
        TemplateQueryBinding otherService = binding("binding-3", "tenant-1", "other-mcp-service", "role-1",
            "[\"api_service:other_service_query\"]");
        when(repository.findByDomainCode("customer"))
            .thenReturn(List.of(matching, otherRole, otherService));
        when(roles.findById("role-1")).thenReturn(Optional.of(role("role-1", "FINANCE")));
        when(roles.findById("role-2")).thenReturn(Optional.of(role("role-2", "SECURITY")));
        when(authorization.currentCallerContext()).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext(
                "tenant-1", "user-1", "user", List.of("role-1")));
        when(catalog.listAuthorizedForRoleAndType("role-1", TemplateAssetCatalogService.API)).thenReturn(List.of(
            asset("api_service:customer_query")));

        Map<String, Set<String>> allowed = service.allowedTemplates(
            context(TemplateQueryParentCatalog.SERVICE_ID, "FINANCE"), "customer_template_query");

        assertThat(allowed).containsEntry("api_service", Set.of("customer_query"));
        assertThat(allowed.toString()).doesNotContain("secret_query");
        assertThat(allowed.toString()).doesNotContain("other_service_query");
        TemplateQueryBindingService.PolicyResolution cached = service.resolvePolicy(
            context(TemplateQueryParentCatalog.SERVICE_ID, "FINANCE"), "customer_template_query");
        assertThat(cached.cacheHit()).isTrue();
        assertThat(cached.policyVersion()).hasSize(24);
        assertThat(service.allowedTemplates(
            context(TemplateQueryParentCatalog.SERVICE_ID, "FINANCE"), "other_template_query")).isEmpty();
    }

    @Test
    void matchesDatabaseBindingByRoleNameAsWellAsRoleIdOrCode() {
        TemplateQueryBindingRepository repository = mock(TemplateQueryBindingRepository.class);
        McpSynchronizedRoleRepository roles = mock(McpSynchronizedRoleRepository.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryBindingService service = new TemplateQueryBindingService(
            repository, catalog, new TemplateQueryParentCatalog(), roles, new ObjectMapper(), authorization);
        TemplateQueryBinding binding = binding("binding-1", "tenant-1", TemplateQueryParentCatalog.SERVICE_ID,
            "role-1", "[\"api_service:customer_query\"]");
        McpSynchronizedRole finance = role("role-1", "FINANCE");
        finance.setRoleName("Finance Manager");
        when(repository.findByDomainCode("customer"))
            .thenReturn(List.of(binding));
        when(roles.findById("role-1")).thenReturn(Optional.of(finance));
        when(authorization.currentCallerContext()).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext(
                "tenant-1", "user-1", "user", List.of("Finance Manager")));
        when(catalog.listAuthorizedForRoleAndType("role-1", TemplateAssetCatalogService.API))
            .thenReturn(List.of(asset("api_service:customer_query")));

        assertThat(service.allowedTemplates(
            context(TemplateQueryParentCatalog.SERVICE_ID, "Finance Manager"), "customer_template_query"))
            .containsEntry("api_service", Set.of("customer_query"));
    }

    @Test
    void ignoresUntrustedRequestRoleWhenCanonicalCallerRoleDoesNotMatch() {
        TemplateQueryBindingRepository repository = mock(TemplateQueryBindingRepository.class);
        McpSynchronizedRoleRepository roles = mock(McpSynchronizedRoleRepository.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        TemplateQueryBindingService service = new TemplateQueryBindingService(
            repository, mock(TemplateAssetCatalogService.class), new TemplateQueryParentCatalog(),
            roles, new ObjectMapper(), authorization);
        TemplateQueryBinding binding = binding("binding-1", "tenant-1", TemplateQueryParentCatalog.SERVICE_ID, "role-1",
            "[\"api_service:customer_query\"]");
        when(repository.findByDomainCode("customer")).thenReturn(List.of(binding));
        when(roles.findById("role-1")).thenReturn(Optional.of(role("role-1", "FINANCE")));
        when(authorization.currentCallerContext()).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext(
                "tenant-1", "user-1", "user", List.of("role-2")));

        Map<String, Set<String>> allowed = service.allowedTemplates(
            context(TemplateQueryParentCatalog.SERVICE_ID, "FINANCE"), "customer_template_query");

        assertThat(allowed).isEmpty();
    }

    @Test
    void preservesParentRouteWhenMatchingBindingHasEmptyTemplateAuthorizationIntersection() {
        TemplateQueryBindingRepository repository = mock(TemplateQueryBindingRepository.class);
        McpSynchronizedRoleRepository roles = mock(McpSynchronizedRoleRepository.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryBindingService service = new TemplateQueryBindingService(
            repository, catalog, new TemplateQueryParentCatalog(), roles, new ObjectMapper(), authorization);
        TemplateQueryBinding binding = binding("binding-1", "tenant-1", TemplateQueryParentCatalog.SERVICE_ID, "role-1",
            "[\"api_service:disabled_query\"]");
        when(repository.findByDomainCode("customer")).thenReturn(List.of(binding));
        when(roles.findById("role-1")).thenReturn(Optional.of(role("role-1", "FINANCE")));
        when(authorization.currentCallerContext()).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext(
                "tenant-1", "user-1", "user", List.of("role-1")));
        when(catalog.listAuthorizedForRoleAndType("role-1", TemplateAssetCatalogService.API))
            .thenReturn(List.of());

        TemplateQueryBindingService.PolicyResolution resolution = service.resolvePolicy(
            context(TemplateQueryParentCatalog.SERVICE_ID, "FINANCE"), "customer_template_query");

        assertThat(resolution.parentToolNames()).containsExactly("api_template_query");
        assertThat(resolution.allowedTemplates()).isEmpty();
        assertThat(resolution.configuredTemplateCount()).isZero();
    }

    @Test
    void appliesRoleWideTemplatesAndOnlyTheAuthenticatedMembersPersonalTemplates() {
        TemplateQueryBindingRepository repository = mock(TemplateQueryBindingRepository.class);
        McpSynchronizedRoleRepository roles = mock(McpSynchronizedRoleRepository.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryBindingService service = new TemplateQueryBindingService(
            repository, catalog, new TemplateQueryParentCatalog(), roles, new ObjectMapper(), authorization);
        TemplateQueryBinding roleWide = binding("binding-role", "tenant-1", TemplateQueryParentCatalog.SERVICE_ID, "role-1",
            "[\"api_service:common_query\"]");
        TemplateQueryBinding forCaller = binding("binding-user-1", "tenant-1", TemplateQueryParentCatalog.SERVICE_ID, "role-1",
            "[\"api_service:personal_query\"]");
        forCaller.setSubjectType(TemplateQueryBindingService.SUBJECT_USER);
        forCaller.setSubjectId("user-1");
        TemplateQueryBinding forAnotherMember = binding("binding-user-2", "tenant-1", TemplateQueryParentCatalog.SERVICE_ID, "role-1",
            "[\"api_service:secret_query\"]");
        forAnotherMember.setSubjectType(TemplateQueryBindingService.SUBJECT_USER);
        forAnotherMember.setSubjectId("user-2");
        when(repository.findByDomainCode("customer"))
            .thenReturn(List.of(roleWide, forCaller, forAnotherMember));
        when(roles.findById("role-1")).thenReturn(Optional.of(role("role-1", "FINANCE")));
        when(authorization.currentCallerContext()).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext(
                "tenant-1", "user-1", "alice", List.of("role-1")));
        when(catalog.listAuthorizedForRoleAndType("role-1", TemplateAssetCatalogService.API)).thenReturn(List.of(
            asset("api_service:common_query"), asset("api_service:personal_query"),
            asset("api_service:secret_query")));

        Map<String, Set<String>> allowed = service.allowedTemplates(
            context(TemplateQueryParentCatalog.SERVICE_ID, "FINANCE"), "customer_template_query");

        assertThat(allowed).containsEntry("api_service", Set.of("common_query", "personal_query"));
        assertThat(allowed.toString()).doesNotContain("secret_query");
    }

    @Test
    void resolvesFixedPublicationServiceWhenTransportClientIdIsUnavailable() {
        TemplateQueryBindingRepository repository = mock(TemplateQueryBindingRepository.class);
        McpSynchronizedRoleRepository roles = mock(McpSynchronizedRoleRepository.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryBindingService service = new TemplateQueryBindingService(
            repository, catalog, new TemplateQueryParentCatalog(), roles, new ObjectMapper(), authorization);
        TemplateQueryBinding binding = binding("binding-1", "tenant-1",
            TemplateQueryParentCatalog.SERVICE_ID, "role-1", "[\"api_service:customer_query\"]");
        when(repository.findByDomainCode("customer"))
            .thenReturn(List.of(binding));
        when(roles.findById("role-1")).thenReturn(Optional.of(role("role-1", "FINANCE")));
        when(authorization.currentCallerContext()).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext(
                "tenant-1", "user-1", "user", List.of("role-1")));
        when(catalog.listAuthorizedForRoleAndType("role-1", TemplateAssetCatalogService.API))
            .thenReturn(List.of(asset("api_service:customer_query")));

        McpInvocationContext.Context withoutClientId = context(null, "FINANCE");
        assertThat(service.allowedTemplates(withoutClientId, "customer_template_query"))
            .containsEntry("api_service", Set.of("customer_query"));
    }

    @Test
    void resolvesFixedPublicationServiceWhenTransportClientIdDiffersFromLogicalServiceId() {
        TemplateQueryBindingRepository repository = mock(TemplateQueryBindingRepository.class);
        McpSynchronizedRoleRepository roles = mock(McpSynchronizedRoleRepository.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        TemplateAssetCatalogService catalog = mock(TemplateAssetCatalogService.class);
        TemplateQueryBindingService service = new TemplateQueryBindingService(
            repository, catalog, new TemplateQueryParentCatalog(), roles, new ObjectMapper(), authorization);
        TemplateQueryBinding binding = binding("binding-1", "tenant-1",
            TemplateQueryParentCatalog.SERVICE_ID, "role-1", "[\"api_service:customer_query\"]");
        when(repository.findByDomainCode("customer"))
            .thenReturn(List.of(binding));
        when(roles.findById("role-1")).thenReturn(Optional.of(role("role-1", "FINANCE")));
        when(authorization.currentCallerContext()).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext(
                "tenant-1", "user-1", "user", List.of("role-1")));
        when(catalog.listAuthorizedForRoleAndType("role-1", TemplateAssetCatalogService.API))
            .thenReturn(List.of(asset("api_service:customer_query")));

        McpInvocationContext.Context transportContext = context("transport-client-uuid", "FINANCE");
        assertThat(service.allowedTemplates(transportContext, "customer_template_query"))
            .containsEntry("api_service", Set.of("customer_query"));
    }

    private TemplateQueryBinding binding(String id, String tenant, String service, String role, String keys) {
        TemplateQueryBinding binding = new TemplateQueryBinding();
        binding.setId(id);
        binding.setTenantId(tenant);
        binding.setServiceId(service);
        binding.setRoleId(role);
        binding.setDomainCode("customer");
        binding.setParentToolName("api_template_query");
        binding.setSubjectType(TemplateQueryBindingService.SUBJECT_ROLE);
        binding.setSubjectId(role);
        binding.setTemplateKeysJson(keys);
        binding.setEnabled(true);
        return binding;
    }

    private TemplateAssetCatalogService.TemplateAsset asset(String key) {
        String[] parts = key.split(":", 2);
        return new TemplateAssetCatalogService.TemplateAsset(
            key, parts[0], parts[1], parts[1], "", "", "", "");
    }

    private McpSynchronizedRole role(String id, String code) {
        McpSynchronizedRole role = new McpSynchronizedRole();
        role.setId(id);
        role.setTenantId("tenant-1");
        role.setRoleCode(code);
        role.setStatus("ACTIVE");
        return role;
    }

    private McpInvocationContext.Context context(String serviceId, String role) {
        return new McpInvocationContext.Context(
            "caller", "127.0.0.1", "test", "request-1", serviceId,
            "user-1", "user", "tenant-1", role, null, "DEV", "trace-1",
            null, null, null, null
        );
    }
}
