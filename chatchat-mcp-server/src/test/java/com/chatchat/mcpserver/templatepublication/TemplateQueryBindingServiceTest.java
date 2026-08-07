package com.chatchat.mcpserver.templatepublication;

import com.chatchat.mcpserver.authorization.McpSynchronizedRole;
import com.chatchat.mcpserver.authorization.McpSynchronizedRoleRepository;
import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.mcp.McpInvocationContext;
import com.chatchat.mcpserver.mcp.McpServiceRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateQueryBindingServiceTest {

    @Test
    void unionsOnlyBindingsMatchingAuthenticatedServiceTenantAndCallerRole() {
        TemplateQueryBindingRepository repository = mock(TemplateQueryBindingRepository.class);
        McpSynchronizedRoleRepository roles = mock(McpSynchronizedRoleRepository.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        TemplateQueryBindingService service = new TemplateQueryBindingService(
            repository, mock(TemplateAssetCatalogService.class), mock(McpServiceRegistryService.class),
            roles, new ObjectMapper(), authorization);
        TemplateQueryBinding matching = binding("binding-1", "tenant-1", "service-1", "role-1",
            "[\"api_service:customer_query\",\"database_query:balance_query\"]");
        TemplateQueryBinding otherRole = binding("binding-2", "tenant-1", "service-1", "role-2",
            "[\"api_service:secret_query\"]");
        when(repository.findByServiceIdAndEnabledTrue("service-1"))
            .thenReturn(List.of(matching, otherRole));
        when(roles.findById("role-1")).thenReturn(Optional.of(role("role-1", "FINANCE")));
        when(roles.findById("role-2")).thenReturn(Optional.of(role("role-2", "SECURITY")));
        when(authorization.currentCallerContext()).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext("tenant-1", List.of("role-1")));

        Map<String, Set<String>> allowed = service.allowedTemplates(context("service-1", "FINANCE"));

        assertThat(allowed).containsEntry("api_service", Set.of("customer_query"));
        assertThat(allowed).containsEntry("database_query", Set.of("balance_query"));
        assertThat(allowed.toString()).doesNotContain("secret_query");
    }

    @Test
    void ignoresUntrustedRequestRoleWhenCanonicalCallerRoleDoesNotMatch() {
        TemplateQueryBindingRepository repository = mock(TemplateQueryBindingRepository.class);
        McpSynchronizedRoleRepository roles = mock(McpSynchronizedRoleRepository.class);
        McpAuthorizationService authorization = mock(McpAuthorizationService.class);
        TemplateQueryBindingService service = new TemplateQueryBindingService(
            repository, mock(TemplateAssetCatalogService.class), mock(McpServiceRegistryService.class),
            roles, new ObjectMapper(), authorization);
        TemplateQueryBinding binding = binding("binding-1", "tenant-1", "service-1", "role-1",
            "[\"api_service:customer_query\"]");
        when(repository.findByServiceIdAndEnabledTrue("service-1")).thenReturn(List.of(binding));
        when(roles.findById("role-1")).thenReturn(Optional.of(role("role-1", "FINANCE")));
        when(authorization.currentCallerContext()).thenReturn(
            new McpAuthorizationService.CallerAuthorizationContext("tenant-1", List.of("role-2")));

        Map<String, Set<String>> allowed = service.allowedTemplates(context("service-1", "FINANCE"));

        assertThat(allowed).isEmpty();
    }

    private TemplateQueryBinding binding(String id, String tenant, String service, String role, String keys) {
        TemplateQueryBinding binding = new TemplateQueryBinding();
        binding.setId(id);
        binding.setTenantId(tenant);
        binding.setServiceId(service);
        binding.setRoleId(role);
        binding.setTemplateKeysJson(keys);
        binding.setEnabled(true);
        return binding;
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
