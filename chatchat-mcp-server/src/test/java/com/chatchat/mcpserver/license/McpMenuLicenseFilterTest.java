package com.chatchat.mcpserver.license;

import com.chatchat.license.LicensePayload;
import com.chatchat.license.LicenseStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpMenuLicenseFilterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpAdminMenuCatalog catalog = new McpAdminMenuCatalog(objectMapper);

    @Test
    void exposesExactlyTheMenusCarriedByLicense() {
        var access = catalog.access(valid(List.of("databaseMcp", "cacheSettings")));

        assertThat(access).filteredOn(McpAdminMenuCatalog.MenuAccess::authorized)
            .extracting(McpAdminMenuCatalog.MenuAccess::key)
            .containsExactly("businessCategories", "databaseMcp", "cacheSettings");
    }

    @Test
    void existingBusinessModuleLicenseAlsoExposesSharedCategoryMaintenance() {
        var status = valid(List.of("apiServices"));

        assertThat(catalog.authorized(status, "businessCategories")).isTrue();
        assertThat(catalog.menuForPath("/api/v1/business-categories"))
            .get().extracting(McpAdminMenuCatalog.MenuDefinition::key)
            .isEqualTo("businessCategories");
    }

    @Test
    void rejectsDirectRequestToMenuThatIsNotLicensed() throws Exception {
        McpLicenseService licenses = mock(McpLicenseService.class);
        when(licenses.status()).thenReturn(valid(List.of("databaseMcp")));
        McpMenuLicenseFilter filter = new McpMenuLicenseFilter(licenses, catalog, objectMapper);
        MockHttpServletRequest request = request("/api/v1/cache/database-query/stats", "tenant-a");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger calls = new AtomicInteger();

        filter.doFilter(request, response, countingChain(calls));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("MCP_MENU_NOT_LICENSED", "cacheSettings");
        assertThat(calls).hasValue(0);
    }

    @Test
    void usesMostSpecificMenuForSharedAuditRoute() {
        assertThat(catalog.menuForPath("/api/v1/audit-logs/commands/42"))
            .get().extracting(McpAdminMenuCatalog.MenuDefinition::key)
            .isEqualTo("commandAuditLogs");
        assertThat(catalog.menuForPath("/api/v1/audit-logs/42"))
            .get().extracting(McpAdminMenuCatalog.MenuDefinition::key)
            .isEqualTo("auditLogs");
        assertThat(catalog.menuForPath("/api/v1/mcp-search-index/database-queries/rebuild"))
            .get().extracting(McpAdminMenuCatalog.MenuDefinition::key)
            .isEqualTo("databaseMcp");
    }

    @Test
    void expiredLicenseRejectsConcurrentRequestsFromMultipleTenants() throws Exception {
        McpLicenseService licenses = mock(McpLicenseService.class);
        when(licenses.status()).thenReturn(
            LicenseStatus.invalid("EXPIRED", "License 已过期", "SERVER-1", payload(List.of("databaseMcp"))));
        McpMenuLicenseFilter filter = new McpMenuLicenseFilter(licenses, catalog, objectMapper);
        AtomicInteger calls = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<MockHttpServletResponse>> requests = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                String tenant = index % 2 == 0 ? "tenant-a" : "tenant-b";
                requests.add(() -> {
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request("/api/v1/database-query", tenant), response, countingChain(calls));
                    return response;
                });
            }
            var results = executor.invokeAll(requests);
            for (var result : results) {
                MockHttpServletResponse response = result.get();
                assertThat(response.getStatus()).isEqualTo(403);
                assertThat(response.getContentAsString()).contains("MCP_LICENSE_EXPIRED", "\"licenseStatus\":\"EXPIRED\"");
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void licensedConcurrentRequestsFromMultipleTenantsAllReachHandler() throws Exception {
        McpLicenseService licenses = mock(McpLicenseService.class);
        when(licenses.status()).thenReturn(valid(List.of("databaseMcp")));
        McpMenuLicenseFilter filter = new McpMenuLicenseFilter(licenses, catalog, objectMapper);
        AtomicInteger calls = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> requests = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                String tenant = "tenant-" + (index % 4);
                requests.add(() -> {
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request("/api/v1/database-query/categories", tenant), response, countingChain(calls));
                    return response.getStatus();
                });
            }
            for (var result : executor.invokeAll(requests)) assertThat(result.get()).isEqualTo(200);
        } finally {
            executor.shutdownNow();
        }
        assertThat(calls).hasValue(64);
    }

    private MockHttpServletRequest request(String path, String tenant) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-Tenant-Id", tenant);
        return request;
    }

    private FilterChain countingChain(AtomicInteger calls) {
        return (request, response) -> calls.incrementAndGet();
    }

    private LicenseStatus valid(List<String> modules) {
        return LicenseStatus.valid("SERVER-1", payload(modules));
    }

    private LicensePayload payload(List<String> modules) {
        return new LicensePayload("LIC-MENU", "Customer", "C1", "LiveMCP", "enterprise",
            modules, 100, "*", LocalDate.now().plusYears(1), Map.of(), LocalDate.now());
    }
}
