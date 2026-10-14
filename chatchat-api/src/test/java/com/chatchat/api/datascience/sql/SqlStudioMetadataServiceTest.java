package com.chatchat.api.datascience.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlStudioMetadataServiceTest {
    SqlStudioService studio = mock(SqlStudioService.class);
    SqlStudioMetadataGateway gateway = mock(SqlStudioMetadataGateway.class);
    MockHttpServletRequest request = new MockHttpServletRequest();
    ObjectMapper mapper = new ObjectMapper();
    JdbcTemplate jdbc;
    SqlStudioMetadataService service;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:metadata_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql-studio/schema-h2.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds); service = new SqlStudioMetadataService(studio, gateway, jdbc, mapper);
        when(studio.project(request, "project")).thenReturn(new SqlStudioService.Project("project", "SQL", "spark", "session", "tenant", "owner"));
        when(gateway.invoke(eq(request), any(), anyMap())).thenAnswer(call -> {
            Map<String, Object> args = call.getArgument(2);
            return new SqlStudioMetadataGateway.Result("registered-mcp", Map.of("items", List.of(Map.of("assetRef", args.get("assetId"), "sourceDialect", "mysql"))));
        });
    }
    SqlStudioMetadataService.Create input() {
        return new SqlStudioMetadataService.Create("ETL", "spark", List.of(new SqlStudioMetadataService.BindingInput(null, "asset", "SOURCE", "db", "public")));
    }
    @Test void persistsDiscoveryAndBindingIdentityAndRestoresThroughNewServiceInstance() {
        var created = service.create(request, "project", input());
        var restarted = new SqlStudioMetadataService(studio, gateway, jdbc, mapper);
        assertThat(restarted.get(request, "project", created.discoveryId())).isEqualTo(created);
        assertThat(created.targetDialect()).isEqualTo("spark");
        assertThat(created.bindings().get(0).sourceDialect()).isEqualTo("mysql");
        assertThat(created.bindings().get(0).serviceId()).isEqualTo("registered-mcp");
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_metadata_context", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select bindings_json from ds_sql_metadata_context", String.class)).doesNotContain("password", "jdbcUrl");
    }
    @Test void operationsUsePersistedServiceAssetAndNamespaceInsteadOfUntrustedArguments() {
        var context = service.create(request, "project", input()); clearInvocations(gateway);
        service.operate(request, "project", context.discoveryId(), new SqlStudioMetadataService.Operation(context.bindings().get(0).bindingId(), Map.of("operation", "DESCRIBE", "tableName", "orders")));
        verify(gateway).invoke(request, "registered-mcp", Map.of("contractVersion", SqlStudioMetadataGateway.CONTRACT, "operation", "DESCRIBE", "assetId", "asset", "database", "db", "schema", "public", "tableName", "orders"));
        for (var args : List.of(Map.<String,Object>of("operation", "SEARCH", "assetId", "other"), Map.<String,Object>of("operation", "SEARCH", "schema", "hidden"), Map.<String,Object>of("operation", "SEARCH", "sql", "SELECT 1"), Map.<String,Object>of("operation", "REFRESH"))) {
            assertThatThrownBy(() -> service.operate(request, "project", context.discoveryId(), new SqlStudioMetadataService.Operation(context.bindings().get(0).bindingId(), args)))
                .isInstanceOf(ResponseStatusException.class);
        }
        verifyNoMoreInteractions(gateway);
    }
    @Test void rejectsContextAfterProjectOwnerOrTenantChangesBeforeAnyGatewayCall() {
        var context = service.create(request, "project", input()); clearInvocations(gateway);
        when(studio.project(request, "project")).thenReturn(new SqlStudioService.Project("project", "SQL", "spark", "session", "other-tenant", "owner"));
        assertThatThrownBy(() -> service.get(request, "project", context.discoveryId())).isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(gateway);
    }
    @Test void restoredContextDoesNotKeepRevokedMetadataPermissions() {
        var context = service.create(request, "project", input());
        when(gateway.invoke(eq(request), any(), anyMap())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.get(request, "project", context.discoveryId())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.operate(request, "project", context.discoveryId(), new SqlStudioMetadataService.Operation(context.bindings().get(0).bindingId(), Map.of("operation", "SEARCH"))))
            .isInstanceOf(ResponseStatusException.class);
    }
    @Test void unverifiedAssetCannotCreateContextAndUnknownBindingCannotCallGateway() {
        when(gateway.invoke(eq(request), any(), anyMap())).thenReturn(new SqlStudioMetadataGateway.Result("mcp", Map.of("items", List.of())));
        assertThatThrownBy(() -> service.create(request, "project", input())).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("select count(*) from ds_sql_metadata_context", Integer.class)).isZero();
    }
}
