package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.cache.rocksdb.McpRocksDbStore;
import com.chatchat.mcpserver.search.engine.LuceneMcpSearchService;
import com.chatchat.mcpserver.search.engine.LuceneSearchProperties;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetadataUpgradeTest {
    @ParameterizedTest @ValueSource(strings = {"trino", "neo4j", "opensearch", "elasticsearch"})
    void typedSnapshotsSurviveRestartAndFailedRefreshAndRejectChangedScope(String type) throws Exception {
        var asset = NativeMetadataCollectorTest.asset(type, type.equals("trino") ? "jdbc:trino://localhost/hive/sales" : "http://localhost:9200");
        asset.setMetadataScopeValue(type.equals("trino") ? "hive.sales" : "data");
        var collector = mock(MetadataCollector.class);
        when(collector.supports(type)).thenReturn(true);
        var object = object(asset.getId(), type, "data", "orders", "id");
        when(collector.collect(any(), anyList())).thenReturn(List.of(object));
        Map<String, byte[]> bytes = new HashMap<>();
        var rocks = mock(McpRocksDbStore.class); when(rocks.isUsable()).thenReturn(true);
        when(rocks.get(anyString())).thenAnswer(call -> bytes.get(call.getArgument(0)));
        doAnswer(call -> { bytes.put(call.getArgument(0), call.getArgument(1)); return null; }).when(rocks).put(anyString(), any());
        var service = service(collector, rocks);
        var refreshed = service.refreshDatasource(asset);
        assertThat(refreshed.error()).isNull();
        assertThat(refreshed.objectCount()).isEqualTo(1); assertThat(refreshed.fieldCount()).isEqualTo(1);
        assertThat(refreshed.persistedToRocksDb()).isTrue();
        var restarted = service(collector, rocks);
        assertThat(restarted.indexFor(asset).objects()).containsExactly(object);
        long timestamp = restarted.indexFor(asset).refreshedAtMs();
        when(collector.collect(any(), anyList())).thenThrow(new IllegalStateException("Forbidden"));
        assertThat(restarted.refreshDatasource(asset).error()).contains("Forbidden");
        assertThat(restarted.indexFor(asset).objects()).containsExactly(object);
        assertThat(restarted.indexFor(asset).refreshedAtMs()).isEqualTo(timestamp);
        assertThat(service(collector, rocks).indexFor(asset).objects()).containsExactly(object);
        asset.setMetadataScopeValue("restricted");
        assertThat(restarted.indexFor(asset).error()).isEqualTo("metadata_index_not_refreshed");
        assertThat(service(collector, rocks).indexFor(asset).objects()).isEmpty();
    }

    @Test void permissionsAndSensitiveFieldsAreAppliedBeforeRankingAndContextIsReferenceScoped() {
        var asset = NativeMetadataCollectorTest.asset("elasticsearch", "http://localhost:9200");
        asset.setAllowedTablesJson("[\"public\"]"); asset.setSensitiveFieldsJson("[\"secret\"]");
        var allowed = new MetadataObject(asset.getId(), "elasticsearch", "INDEX", "public", "public", List.of("public"), "",
            List.of(new MetadataObject.Field("name", List.of("name"), "keyword", null, "", Map.of()),
                new MetadataObject.Field("secret", List.of("secret"), "keyword", null, "", Map.of())), Map.of());
        var blocked = object(asset.getId(), "elasticsearch", "private", "private", "secret");
        var indexes = mock(MetadataIndexService.class); when(indexes.indexFor(asset)).thenReturn(MetadataIndex.collected(asset.getId(), "elasticsearch", List.of(allowed, blocked)));
        var datasources = mock(SqlDatasourceConfigService.class); when(datasources.listEnabled()).thenReturn(List.of(asset));
        var lucene = spy(new LuceneMcpSearchService(new LuceneSearchProperties()));
        var metadata = new DatasourceMetadataSearchService(datasources, indexes, lucene, new ObjectMapper());
        var result = metadata.search(Map.of("query", "secret"));
        assertThat(result.get("count")).isEqualTo(0);
        var docs = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(lucene).rankScopedMetadata(docs.capture(), eq("secret"));
        assertThat(docs.getValue()).hasSize(1);
        assertThat(((LuceneMcpSearchService.AssetDoc) docs.getValue().get(0)).extraText()).doesNotContain("secret");
        assertThat(metadata.context(asset, List.of(), List.of()).get("objects")).isEqualTo(List.of());
        var context = metadata.context(asset, List.of("public"), List.of());
        assertThat(context.get("objects").toString()).contains("name").doesNotContain("secret", "private");
        assertThat(context.get("reviewStatus")).isEqualTo("AVAILABLE");
    }

    @Test void structuralChangesRequireReviewUntilTheQueryIsReviewedAndOldJsonIsCompatible() throws Exception {
        var first = MetadataIndex.collected("asset", "elasticsearch", List.of(object("asset", "elasticsearch", "news", "news", "title")));
        var changed = MetadataIndex.collected("asset", "elasticsearch", List.of(object("asset", "elasticsearch", "news", "news", "headline"))).withChanges(first);
        assertThat(changed.changes()).hasSize(1);
        var asset = NativeMetadataCollectorTest.asset("elasticsearch", "http://localhost:9200");
        var indexes = mock(MetadataIndexService.class); when(indexes.indexFor(asset)).thenReturn(changed);
        var service = new DatasourceMetadataSearchService(mock(SqlDatasourceConfigService.class), indexes,
            new LuceneMcpSearchService(new LuceneSearchProperties()), new ObjectMapper());
        assertThat(service.context(asset, List.of("news"), List.of(), 0).get("reviewStatus")).isEqualTo("NEEDS_REVIEW");
        assertThat(service.context(asset, List.of("news"), List.of(), changed.refreshedAtMs() + 1).get("reviewStatus")).isEqualTo("AVAILABLE");
        var json = new ObjectMapper();
        var old = json.readTree(json.writeValueAsString(first));
        ((com.fasterxml.jackson.databind.node.ObjectNode) old).remove(List.of("objects", "configurationFingerprint", "changes"));
        assertThat(json.treeToValue(old, MetadataIndex.class).changes()).isEmpty();
    }

    @Test void templateGraphReferencesKeepNodeAndRelationshipKindsAndDatabasesSeparate() throws Exception {
        var asset = NativeMetadataCollectorTest.asset("neo4j", "http://localhost:7474");
        var datasources = mock(SqlDatasourceConfigService.class); when(datasources.getEnabled(asset.getId())).thenReturn(asset);
        var metadata = mock(DatasourceMetadataSearchService.class);
        when(metadata.context(any(), anyList(), anyList(), anyLong())).thenReturn(Map.of());
        var query = new DatabaseQueryConfig(); query.setDatasourceId(asset.getId());
        query.setSqlStepsJson("""
            [{"enabled":true,"queryOptions":{"database":"analytics"},"sqlContent":"MATCH (p:Person {memo: 'Secret'})-[r:WORKS_AT]->(c:Company) RETURN p"},
             {"enabled":false,"sqlContent":"MATCH (s:Secret) RETURN s"}]
            """);
        new QueryMetadataContextService(datasources, metadata, new ObjectMapper()).context(query);
        verify(metadata).context(eq(asset), eq(List.of("analytics.NODE_LABEL.Person", "analytics.RELATIONSHIP_TYPE.WORKS_AT", "analytics.NODE_LABEL.Company")), eq(List.of("analytics")), eq(0L));
    }

    @Test void templateTrinoReferencesResolveDefaultCatalogAndStepSchema() throws Exception {
        var asset = NativeMetadataCollectorTest.asset("trino", "jdbc:trino://localhost:8080/hive/sales");
        var datasources = mock(SqlDatasourceConfigService.class); when(datasources.getEnabled(asset.getId())).thenReturn(asset);
        var metadata = mock(DatasourceMetadataSearchService.class); when(metadata.context(any(), anyList(), anyList(), anyLong())).thenReturn(Map.of());
        var query = new DatabaseQueryConfig(); query.setDatasourceId(asset.getId());
        query.setSqlStepsJson("""
            [{"enabled":true,"queryOptions":{"schema":"archive"},"sqlContent":"SELECT * FROM orders JOIN iceberg.sales.events e ON 1=1"}]
            """);
        new QueryMetadataContextService(datasources, metadata, new ObjectMapper()).context(query);
        verify(metadata).context(eq(asset), eq(List.of("hive.archive.orders", "iceberg.sales.events")), eq(List.of()), eq(0L));
    }

    @Test void objectIdentifiersDoNotCollapseDottedNamesOrCase() {
        var a = new MetadataObject("asset", "trino", "TABLE", "a.b", "c", List.of("a.b", "c"), "", List.of(), Map.of());
        var b = new MetadataObject("asset", "trino", "TABLE", "a", "b.c", List.of("a", "b.c"), "", List.of(), Map.of());
        assertThat(a.qualifiedName()).isEqualTo(b.qualifiedName()); assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(MetadataIndex.tableKey("a.b", "c", "d")).isNotEqualTo(MetadataIndex.tableKey("a", "b.c", "d"));
    }

    @Test void ambiguousAndPartlyMissingReferencesNeverReturnBroaderSchema() {
        var asset = NativeMetadataCollectorTest.asset("trino", "jdbc:trino://localhost/hive/sales");
        var a = new MetadataObject(asset.getId(), "trino", "TABLE", "hive.sales", "orders", List.of("hive", "sales", "orders"), "", List.of(), Map.of());
        var b = new MetadataObject(asset.getId(), "trino", "TABLE", "hive.archive", "orders", List.of("hive", "archive", "orders"), "", List.of(), Map.of());
        var indexes = mock(MetadataIndexService.class); when(indexes.indexFor(asset)).thenReturn(MetadataIndex.collected(asset.getId(), "trino", List.of(a,b)));
        var metadata = new DatasourceMetadataSearchService(mock(SqlDatasourceConfigService.class), indexes,
            new LuceneMcpSearchService(new LuceneSearchProperties()), new ObjectMapper());
        assertThat(metadata.context(asset, List.of("orders"), List.of()).get("objects")).isEqualTo(List.of());
        assertThat(metadata.context(asset, List.of("orders"), List.of()).get("reviewStatus")).isEqualTo("REFERENCE_SCOPE_REQUIRED");
        assertThat(metadata.context(asset, List.of("hive.sales.orders", "hive.sales.missing"), List.of()).get("reviewStatus")).isEqualTo("REFERENCE_NOT_INDEXED");
    }

    private MetadataIndexService service(MetadataCollector collector, McpRocksDbStore rocks) {
        var service = new MetadataIndexService(); ReflectionTestUtils.setField(service, "collectors", List.of(collector));
        ReflectionTestUtils.setField(service, "rocksDbStore", rocks); return service;
    }
    private static MetadataObject object(String asset, String type, String namespace, String name, String field) {
        return new MetadataObject(asset, type, type.equals("trino") ? "TABLE" : "INDEX", type.equals("trino") ? "hive.sales" : namespace,
            name, type.equals("trino") ? List.of("hive", "sales", name) : List.of(namespace, name), "",
            List.of(new MetadataObject.Field(field, List.of(field), "keyword", null, "", Map.of())), Map.of());
    }
}
