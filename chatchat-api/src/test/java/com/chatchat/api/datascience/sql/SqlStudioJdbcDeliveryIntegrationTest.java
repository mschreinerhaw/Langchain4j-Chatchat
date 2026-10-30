package com.chatchat.api.datascience.sql;

import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** Real H2 JDBC metadata -> production MCP discovery -> persisted Studio scope -> pinned SQLGlot -> delivery. */
@EnabledIfSystemProperty(named="coding.sqlglotPython",matches=".+")
class SqlStudioJdbcDeliveryIntegrationTest {
    final KernelDataScope owner=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
    SqlStudioJdbcMetadataFixture fixture;
    SqlGlotToolchain parser;
    SqlStudioMetadataService.Context context;
    @BeforeEach void setup() throws Exception{
        var source=new DriverManagerDataSource("jdbc:h2:mem:metadata-"+UUID.randomUUID()+";MODE=Oracle;DB_CLOSE_DELAY=-1","sa","");
        fixture=new SqlStudioJdbcMetadataFixture(source,new ObjectMapper());
        parser=new SqlGlotToolchain(System.getProperty("coding.sqlglotPython"),new ObjectMapper());
        context=fixture.metadata.getScoped(owner,"project","discovery");
    }
    Map<String,Object> query(String operation,Object... pairs){
        var args=new LinkedHashMap<String,Object>();args.put("operation",operation);
        for(int i=0;i<pairs.length;i+=2)args.put((String)pairs[i],pairs[i+1]);
        return fixture.metadata.operateScoped(owner,context,new SqlStudioMetadataService.Operation("binding",args)).metadata();
    }
    @SuppressWarnings("unchecked")
    @Test void discoversUnloadedOracleStructureAndDeliversHiveFilesWithFieldsAndExplicitExecutionSkip(){
        var discovered=query("DESCRIBE_TABLE","tableName","ORDERS");
        var tables=(List<Map<String,Object>>)discovered.get("items");assertThat(tables).hasSize(1);
        var file=new SqlOutputBundle.Candidate("sql/orders.sql","SELECT ID, AMOUNT FROM ORDERS");
        var validation=parser.validate("hive",List.of(file),Map.of("tables",tables));
        assertThat(validation).containsEntry("valid",true).containsEntry("fieldValidation","CHECKED");
        assertThat(validation.get("referencedColumns").toString()).contains("amount","id");
        var manifest=SqlStudioDelivery.manifest("hive",List.of(file),validation,Map.of("tables",tables,"snapshots",Map.of("binding",discovered.get("snapshotId"))));
        assertThat(manifest.toString()).contains("targetDialect=hive","SAVED_FOR_REVIEW","sqlExecuted=false","SOURCE_GENERATION_ONLY_NO_TARGET_EXECUTION_AUTHORITY");
        assertThat(parser.validate("hive",List.of(new SqlOutputBundle.Candidate("bad.sql","SELECT MISSING_FIELD FROM ORDERS")),Map.of("tables",tables))).containsEntry("valid",false);
        assertThatThrownBy(()->SqlStudioDelivery.manifest("hive",List.of(),validation,Map.of())).isInstanceOf(IllegalArgumentException.class);
        var incomplete=new LinkedHashMap<>(validation);incomplete.put("fieldValidation","NOT_EVALUATED");
        assertThatThrownBy(()->SqlStudioDelivery.manifest("hive",List.of(file),incomplete,Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pagesCatalogsSchemasTablesAndSearchesColumnsWithoutLoadingThemIntoTheModel(){
        assertThat(query("LIST_CATALOGS")).containsEntry("totalMatched",1);
        assertThat(query("LIST_SCHEMAS")).containsEntry("totalMatched",1);
        var first=query("LIST_TABLES","pageSize",1,"includeColumns",false);
        assertThat(first).containsEntry("hasMore",true);
        assertThat(query("LIST_TABLES","pageSize",1,"page",2,"expectedSnapshotId",first.get("snapshotId"))).containsEntry("hasMore",false);
        assertThat(query("SEARCH_COLUMNS","query","AMOUNT").toString()).contains("ORDERS","AMOUNT");
        assertThat(query("GET_TABLE_DDL","tableName","ORDERS").toString()).contains("CREATE TABLE","GENERATED_FROM_AUTHORIZED_METADATA","VISIBLE_COLUMNS_ONLY");
        assertThat(query("GET_TABLE_RELATIONSHIPS","tableName","ORDERS").toString()).contains("JDBC_IMPORTED_KEYS","CUSTOMERS","CUSTOMER_ID");
    }
    @Test void ddlChangesInvalidatePinnedPagesAndRevokedScopeCannotReadOldEvidence(){
        var before=query("DESCRIBE","tableName","ORDERS");
        fixture.jdbc.execute("ALTER TABLE APP.ORDERS ADD CHANGED_FIELD INTEGER");
        assertThatThrownBy(()->query("LIST_TABLES","expectedSnapshotId",before.get("snapshotId"))).hasMessageContaining("METADATA_SNAPSHOT_CHANGED");
        assertThat(query("DESCRIBE","tableName","ORDERS").toString()).contains("CHANGED_FIELD");
        fixture.authorized=false;
        assertThatThrownBy(()->query("DESCRIBE","tableName","ORDERS")).isInstanceOf(SecurityException.class);
    }
    @Test void sameNameInOtherSchemaIsAmbiguousAndAnotherOwnerCannotReuseDiscovery(){
        var unbounded=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
        assertThatThrownBy(()->fixture.gateway.invokeScoped(unbounded,"service",Map.of("contractVersion",SqlStudioMetadataGateway.CONTRACT,
            "operation","DESCRIBE","assetId","asset","tableName","ORDERS"))).hasMessageContaining("Ambiguous");
        assertThatThrownBy(()->fixture.metadata.getScoped(new KernelDataScope("tenant","intruder",null,null,"run",null,Map.of()),"project","discovery"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
