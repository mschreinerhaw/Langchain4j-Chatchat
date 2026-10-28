package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.mcpserver.search.engine.LuceneMcpSearchService;
import com.chatchat.mcpserver.search.engine.LuceneSearchProperties;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.resolution.TableLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlMetadataDiscoveryWorkflowTest {
    @TempDir Path temp;
    SqlDatasourceConfigService sources = mock(SqlDatasourceConfigService.class);
    MetadataIndexService indexes = mock(MetadataIndexService.class);
    SqlDatasourceConfig asset = asset("a");
    SqlMetadataDiscoveryWorkflow workflow;
    KernelDataScope caller = new KernelDataScope("tenant", "user", "request", null, null, "DEV", Map.of());

    @BeforeEach void setup() {
        var properties = new LuceneSearchProperties(); properties.setIndexDir(temp.toString());
        workflow = new SqlMetadataDiscoveryWorkflow(new SqlMetadataDiscoveryService(sources, indexes, new LuceneMcpSearchService(properties)));
        when(sources.listEnabled()).thenReturn(List.of(asset));
    }

    @Test void enumeratesOnlyAuthorizedSafeAssetProjectionWithoutLoadingAnyIndex() {
        var hidden = asset("b"); asset.setJdbcUrl("jdbc:secret"); asset.setPassword("secret-password");
        when(sources.listEnabled()).thenReturn(List.of(hidden, asset));
        var output = run(args("LIST_ASSETS"));
        assertThat(output.get("totalMatched")).isEqualTo(1);
        assertThat(output.toString()).contains("assetRef=a", "sourceDialect=mysql", "SQL_METADATA_DISCOVERY");
        assertThat(output.toString()).doesNotContain("secret", "jdbc", "assetRef=b");
        verifyNoInteractions(indexes);
    }

    @Test void paginatesBeyondFiveHundredWithStableOrderAndRequiresPinnedSnapshot() {
        when(indexes.indexFor(asset)).thenReturn(index(IntStream.range(0, 503).mapToObj(i -> table("t" + String.format("%04d", i))).toList(), Map.of(), 100));
        var first = run(args("SEARCH", "assetId", "a", "pageSize", 100));
        var last = run(args("SEARCH", "assetId", "a", "pageSize", 100, "page", 6, "expectedSnapshotId", first.get("snapshotId")));
        assertThat(first.get("totalMatched")).isEqualTo(503);
        assertThat((List<?>) first.get("items")).hasSize(100);
        assertThat((List<?>) last.get("items")).hasSize(3);
        assertThat(last.get("hasMore")).isEqualTo(false);
        assertThatThrownBy(() -> run(args("SEARCH", "assetId", "a", "page", 2))).hasMessageContaining("expectedSnapshotId");
    }

    @Test void schemaChangeRejectsOldPageButCollectionTimeAndSecretsDoNotChangePublicVersion() {
        when(indexes.indexFor(asset)).thenReturn(index(List.of(table("orders")), Map.of("db.orders", List.of(column("id"))), 100));
        var before = run(args("DESCRIBE", "assetId", "a", "tableName", "orders"));
        asset.setPassword("rotated-password");
        when(indexes.indexFor(asset)).thenReturn(index(List.of(table("orders")), Map.of("db.orders", List.of(column("id"))), 200).withFingerprint("secret-derived-fingerprint"));
        assertThat(run(args("DESCRIBE", "assetId", "a", "tableName", "orders")).get("snapshotId")).isEqualTo(before.get("snapshotId"));
        when(indexes.indexFor(asset)).thenReturn(index(List.of(table("orders")), Map.of("db.orders", List.of(column("id"), column("created_at"))), 300));
        assertThatThrownBy(() -> run(args("SEARCH", "assetId", "a", "expectedSnapshotId", before.get("snapshotId"))))
            .hasMessageContaining("METADATA_SNAPSHOT_CHANGED");
        var after = run(args("SEARCH", "assetId", "a"));
        assertThat(after.toString()).contains("created_at", "sourceUpdatedAt=null").doesNotContain("secret-derived");
    }

    @Test void tableAndFieldDenialsApplyBeforeSearchCountAndSnapshot() {
        asset.setSensitiveTablesJson("[\"db.hidden\"]"); asset.setSensitiveFieldsJson("[\"db.orders.secret_token\"]");
        when(indexes.indexFor(asset)).thenReturn(index(List.of(table("orders"), table("hidden")),
            Map.of("db.orders", List.of(column("id"), column("secret_token"))), 100));
        var output = run(args("SEARCH", "assetId", "a"));
        assertThat(output.get("totalMatched")).isEqualTo(1);
        assertThat(output.toString()).doesNotContain("hidden", "secret_token");
        assertThat(run(args("SEARCH", "assetId", "a", "query", "secret_token")).get("totalMatched")).isEqualTo(0);
        assertThat(run(args("DESCRIBE", "assetId", "a", "tableName", "hidden")).get("totalMatched")).isEqualTo(0);
    }

    @Test void authorizationIsReevaluatedOnEveryInvocationIncludingPinnedPages() {
        var args = args("SEARCH", "assetId", "a");
        assertThatThrownBy(() -> run(args, ignored -> false)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(indexes);
        when(indexes.indexFor(asset)).thenReturn(index(List.of(table("orders")), Map.of(), 100));
        var first = run(args);
        clearInvocations(indexes);
        assertThatThrownBy(() -> run(args("SEARCH", "assetId", "a", "page", 2, "expectedSnapshotId", first.get("snapshotId")), ignored -> false))
            .isInstanceOf(SecurityException.class);
        verifyNoInteractions(indexes);
    }

    @Test void namespacesComeOnlyFromVisibleTablesAndAmbiguousDescribeRequiresQualification() {
        var other = new TableLocation("a", "db", "other", "orders", "TABLE", null, 0);
        when(indexes.indexFor(asset)).thenReturn(index(List.of(table("orders"), other), Map.of(), 100));
        assertThat((List<?>) run(args("LIST_NAMESPACES", "assetId", "a")).get("items")).hasSize(2);
        assertThatThrownBy(() -> run(args("DESCRIBE", "assetId", "a", "tableName", "orders"))).hasMessageContaining("Ambiguous");
        assertThat(run(args("DESCRIBE", "assetId", "a", "schema", "other", "tableName", "orders")).get("totalMatched")).isEqualTo(1);
    }

    @Test void missingSnapshotReportsNeedsRefreshWithoutImplicitNetworkOrRawErrorLeak() {
        when(indexes.indexFor(asset)).thenReturn(MetadataIndex.failed("a", "mysql", "jdbc:password-internal-error"));
        var output = run(args("SEARCH", "assetId", "a"));
        assertThat(output.get("status")).isEqualTo("NEEDS_REFRESH");
        assertThat(output.get("snapshotId")).isNull();
        assertThat(output.toString()).doesNotContain("password-internal-error");
        verify(indexes, never()).ensureIndex(any());
    }

    @Test void rejectsSpoofedIdentityExecutionArgumentsInvalidPaginationAndUnknownOperations() {
        for (var args : List.of(args("SEARCH", "assetId", "a", "tenantId", "admin"), args("SEARCH", "sql", "SELECT 1"),
            args("REFRESH"), args("SEARCH", "assetId", "a", "pageSize", 101), args("SEARCH", "assetId", "a", "page", 1.5))) {
            assertThatThrownBy(() -> run(args)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> workflow.execute(new SqlMetadataDiscoveryWorkflow.Request(args("LIST_ASSETS"), id -> true)))
            .isInstanceOf(SecurityException.class);
        verifyNoInteractions(indexes);
    }

    @Test void selectsEachOperationIndependentlyWithoutPresetExplorationSequence() {
        when(indexes.indexFor(asset)).thenReturn(index(List.of(table("orders")), Map.of("db.orders", List.of(column("id"))), 100));
        var result = run(args("DESCRIBE", "assetId", "a", "tableName", "orders"));
        assertThat(result.get("doesNotExecuteSql")).isEqualTo(true);
        assertThat(result.toString()).contains("operation=DESCRIBE", "strategyOwner=MODEL", "relationsStatus=NOT_COLLECTED");
        verify(sources, times(1)).listEnabled(); verify(indexes, times(1)).indexFor(asset);
        verifyNoMoreInteractions(indexes);
    }

    @Test void lightweightDirectoryKeepsFullSnapshotButLoadsNoColumnPayload() {
        when(indexes.indexFor(asset)).thenReturn(index(List.of(table("orders")), Map.of("db.orders", List.of(column("id"))), 100));
        var full=run(args("DESCRIBE","assetId","a","tableName","orders"));
        var lean=run(args("SEARCH","assetId","a","includeColumns",false));
        assertThat(lean.get("snapshotId")).isEqualTo(full.get("snapshotId"));
        assertThat(lean.get("evidenceClass")).isEqualTo("REGISTERED_METADATA");
        assertThat(lean.get("verificationStatus")).isEqualTo("NOT_VERIFIED_LIVE");
        assertThat(lean.toString()).doesNotContain("columns=");
        assertThat(full.toString()).contains("columns=", "name=id");
        assertThatThrownBy(()->run(args("SEARCH","assetId","a","includeColumns","false"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyIndexCollisionCannotAttachFieldsFromAnotherPhysicalTable() {
        var other=new TableLocation("a","db","private","orders","TABLE",null,0);
        var privateColumn=new MetadataColumn("a","db","private","orders","private_id","int",null,null,null,true,1);
        var foreignColumn=new MetadataColumn("other-asset","db","public","orders","foreign_id","int",null,null,null,true,1);
        when(indexes.indexFor(asset)).thenReturn(index(List.of(table("orders"),other),Map.of("db.orders",List.of(column("id"),privateColumn,foreignColumn)),100));
        var publicResult=run(args("DESCRIBE","assetId","a","schema","public","tableName","orders"));
        var privateResult=run(args("DESCRIBE","assetId","a","schema","private","tableName","orders"));
        assertThat(publicResult.toString()).contains("name=id").doesNotContain("private_id","foreign_id");
        assertThat(privateResult.toString()).contains("name=private_id").doesNotContain("name=id","foreign_id");
    }
    @Test void exposesOnlyCompleteVisibleForeignKeysAndPinsThemInTheSnapshot() {
        var parent = table("parent");
        var parentId = new MetadataColumn("a","db","public","parent","id","int",null,null,null,false,1);
        var parentPart = new MetadataColumn("a","db","public","parent","part","int",null,null,null,false,2);
        var key = MetadataIndex.relationshipKey("db","public","orders");
        var snapshot = index(List.of(table("orders"),parent),Map.of("db.orders",List.of(column("id"),column("part")),"db.parent",List.of(parentId,parentPart)),100)
            .withForeignKeys(Map.of(key,List.of(new MetadataForeignKey("internal_constraint","id","db","public","parent","id",1),
                new MetadataForeignKey("internal_constraint","part","db","public","parent","part",2))));
        when(indexes.indexFor(asset)).thenReturn(snapshot);
        var before = run(args("DESCRIBE","assetId","a","tableName","orders"));
        assertThat(before.toString()).contains("COLLECTED_VISIBLE","JDBC_IMPORTED_KEYS","referencedTable=parent").doesNotContain("internal_constraint");
        asset.setSensitiveFieldsJson("[\"db.parent.part\"]");
        var after = run(args("DESCRIBE","assetId","a","tableName","orders"));
        assertThat(after.toString()).contains("foreignKeys=[]").doesNotContain("JDBC_IMPORTED_KEYS","referencedTable=parent");
        assertThat(after.get("snapshotId")).isNotEqualTo(before.get("snapshotId"));
        asset.setSensitiveFieldsJson(null); asset.setSensitiveTablesJson("[\"db.parent\"]");
        assertThat(run(args("DESCRIBE","assetId","a","tableName","orders")).toString()).doesNotContain("referencedTable=parent");
    }
    private Map<String, Object> run(Map<String, Object> args) { return run(args, id -> id.equals("a")); }
    private Map<String, Object> run(Map<String, Object> args, Predicate<String> allowed) {
        return workflow.execute(new SqlMetadataDiscoveryWorkflow.Request(args, allowed), caller);
    }
    private Map<String, Object> args(String operation, Object... pairs) {
        Map<String, Object> args = new LinkedHashMap<>(); args.put("contractVersion", SqlMetadataDiscoveryService.CONTRACT); args.put("operation", operation);
        for (int i = 0; i < pairs.length; i += 2) args.put((String) pairs[i], pairs[i + 1]); return args;
    }
    private static SqlDatasourceConfig asset(String id) {
        var asset = new SqlDatasourceConfig(); asset.setId(id); asset.setName(id); asset.setDatabaseType("mysql"); return asset;
    }
    private TableLocation table(String name) { return new TableLocation("a", "db", "public", name, "TABLE", null, 0); }
    private MetadataColumn column(String name) { return new MetadataColumn("a", "db", "public", "orders", name, "int", null, null, null, true, 1); }
    private MetadataIndex index(List<TableLocation> tables, Map<String, List<MetadataColumn>> columns, long collectedAt) {
        return new MetadataIndex("a", "mysql", tables, Map.of(), Map.of(), columns, List.of("db"), collectedAt, false, null);
    }
}
