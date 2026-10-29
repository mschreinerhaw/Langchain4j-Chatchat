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
    @Test void provenanceExtensionPreservesOldFrozenSnapshotsWithoutIgnoringChangedStructureOrNewIdentities(){
        var old=Map.<String,Object>of("tables",List.of(Map.of("tableName","orders","snapshotId","s","columns",List.of(Map.of("name","id")))));
        var fresh=new LinkedHashMap<String,Object>((Map<String,Object>)((List<?>)old.get("tables")).get(0));
        fresh.put("discoveryId","d");fresh.put("bindingId","b");fresh.put("tableRef","t");
        var current=Map.<String,Object>of("tables",List.of(fresh));
        assertThat(SqlStudioMetadataService.sameCodingSnapshot(old,current)).isTrue();
        assertThat(SqlStudioMetadataService.sameCodingSnapshot(current,current)).isTrue();
        var changed=new LinkedHashMap<>(fresh);changed.put("columns",List.of(Map.of("name","hidden")));
        assertThat(SqlStudioMetadataService.sameCodingSnapshot(old,Map.of("tables",List.of(changed)))).isFalse();
        changed=new LinkedHashMap<>(fresh);changed.put("bindingId","other");
        assertThat(SqlStudioMetadataService.sameCodingSnapshot(current,Map.of("tables",List.of(changed)))).isFalse();
        changed=new LinkedHashMap<>(fresh);changed.put("snapshotId","new");
        assertThat(SqlStudioMetadataService.sameCodingSnapshot(old,Map.of("tables",List.of(changed)))).isFalse();
    }
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
    @Test void lightweightOperationsForwardFlagAndKeepPersistedBinding() {
        var context=service.create(request,"project",input());clearInvocations(gateway);
        service.operate(request,"project",context.discoveryId(),new SqlStudioMetadataService.Operation(context.bindings().get(0).bindingId(),Map.of("operation","SEARCH","includeColumns",false)));
        verify(gateway).invoke(eq(request),eq("registered-mcp"),argThat(args->Boolean.FALSE.equals(args.get("includeColumns")) && "db".equals(args.get("database")) && "public".equals(args.get("schema"))));
    }
    @Test void providerDirectoryStillRequiresProjectOwnership() {
        when(studio.project(request,"project")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(()->service.providers(request,"project")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(gateway);
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
    @Test void codingCapturesOnlySelectedAuthorizedColumnsAndRejectsSnapshotChanges(){
        var context=service.create(request,"project",input());
        when(gateway.invoke(eq(request),any(),argThat(args->args!=null && "DESCRIBE".equals(args.get("operation")))))
            .thenReturn(new SqlStudioMetadataGateway.Result("registered-mcp",Map.of("status","SUCCEEDED","snapshotId","snapshot-1",
                "doesNotExecuteSql",true,"items",List.of(Map.of("tableName","orders","tableRef","orders-ref","database","db","schema","public",
                    "columns",List.of(Map.of("name","id","columnType","INT")),"connectionSecret","must not be captured")))));
        var selection=new SqlStudioMetadataService.CodingSelection(context.discoveryId(),context.bindings().get(0).bindingId(),
            "snapshot-1",List.of(new SqlStudioMetadataService.CodingTable("orders","db","public")));
        var captured=service.captureCoding(request,"project",selection);
        assertThat(captured.toString()).contains("orders","id","snapshot-1").doesNotContain("connectionSecret","must not be captured");
        var source=(Map<?,?>)((List<?>)captured.get("tables")).get(0);
        assertThat(source.get("discoveryId")).isEqualTo(context.discoveryId());
        assertThat(source.get("bindingId")).isEqualTo(selection.bindingId());
        assertThat(source.get("tableRef")).isEqualTo("orders-ref");
        var stale=new SqlStudioMetadataService.CodingSelection(selection.discoveryId(),selection.bindingId(),"changed",selection.tables());
        assertThatThrownBy(()->service.captureCoding(request,"project",stale)).isInstanceOf(ResponseStatusException.class);
        when(gateway.invoke(eq(request),any(),anyMap())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(()->service.captureCoding(request,"project",selection)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void groupedSelectionPreservesCrossDatabaseIdentitiesAndRejectsDuplicatesOrWidening(){
        var first=service.create(request,"project",input());
        var second=service.create(request,"project",new SqlStudioMetadataService.Create("QUERY","spark",List.of(new SqlStudioMetadataService.BindingInput("registered-mcp","other","SOURCE","warehouse","public"))));
        when(gateway.invoke(eq(request),any(),argThat(args->args!=null && "DESCRIBE".equals(args.get("operation")))))
            .thenAnswer(call->{Map<String,Object> args=call.getArgument(2);return new SqlStudioMetadataGateway.Result("registered-mcp",Map.of("status","SUCCEEDED","snapshotId","v1","doesNotExecuteSql",true,"items",List.of(Map.of("tableName","orders","database",args.get("database"),"schema","public","columns",List.of(Map.of("name","id","dataType","bigint","comment","key","nullable",false)),"password","hidden"))));});
        var a=new SqlStudioMetadataService.CodingSelection(first.discoveryId(),first.bindings().get(0).bindingId(),"v1",List.of(new SqlStudioMetadataService.CodingTable("orders","db","public")));
        var b=new SqlStudioMetadataService.CodingSelection(second.discoveryId(),second.bindings().get(0).bindingId(),"v1",List.of(new SqlStudioMetadataService.CodingTable("orders","warehouse","public")));
        var group=new SqlStudioMetadataService.CodingSelection(null,null,null,null,List.of(a,b));
        var captured=service.captureCoding(request,"project",group);
        assertThat((List<?>)captured.get("tables")).hasSize(2);
        assertThat(captured.toString()).contains("warehouse","db","assetRef=other","type=bigint","nullable=false").doesNotContain("password","hidden");
        assertThatThrownBy(()->service.captureCoding(request,"project",new SqlStudioMetadataService.CodingSelection(null,null,null,null,List.of(a,a)))).isInstanceOf(ResponseStatusException.class);
        var widened=new SqlStudioMetadataService.CodingSelection(a.discoveryId(),a.bindingId(),"v1",List.of(new SqlStudioMetadataService.CodingTable("orders","warehouse","public")));
        assertThatThrownBy(()->service.captureCoding(request,"project",widened)).isInstanceOf(ResponseStatusException.class);
        when(gateway.invoke(eq(request),any(),anyMap())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(()->service.captureCoding(request,"project",group)).isInstanceOf(ResponseStatusException.class);
    }

}
