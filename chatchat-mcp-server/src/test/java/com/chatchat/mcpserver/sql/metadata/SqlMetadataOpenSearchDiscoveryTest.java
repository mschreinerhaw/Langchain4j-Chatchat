package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.search.engine.LuceneMcpSearchService;
import com.chatchat.mcpserver.sql.datasource.*;
import com.chatchat.mcpserver.sql.resolution.TableLocation;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlMetadataOpenSearchDiscoveryTest {
    final SqlDatasourceConfigService sources=mock(SqlDatasourceConfigService.class);
    final MetadataIndexService indexes=mock(MetadataIndexService.class);
    final LuceneMcpSearchService search=mock(LuceneMcpSearchService.class);
    final SqlMetadataDiscoveryService service=new SqlMetadataDiscoveryService(sources,indexes,search);
    final SqlDatasourceConfig asset=new SqlDatasourceConfig();
    MetadataIndex index;
    @BeforeEach void setup(){
        asset.setId("owned");asset.setName("Owned source");asset.setDatabaseType("mysql");
        var api=new TableLocation("owned","db","db","ld_dataservice_api","TABLE",null,0);
        var group=new TableLocation("owned","db","db","ld_dataservice_group","TABLE",null,0);
        index=new MetadataIndex("owned","mysql",List.of(api,group),Map.of(),Map.of(),Map.of(
            "db.ld_dataservice_api",List.of(field(api,"project_id"),field(api,"group_id"),field(api,"sql_content")),
            "db.ld_dataservice_group",List.of(field(group,"project_id"),field(group,"id"))),List.of("db"),100,false,null);
        when(sources.listEnabled()).thenReturn(List.of(asset));when(indexes.indexFor(asset)).thenReturn(index);
        when(search.indexedMetadata(eq("owned"),anyList(),any(),any())).thenAnswer(call->{
            String exact=call.getArgument(3);List<String> refs=call.getArgument(1);
            return Optional.of(service.indexEvidence(asset,index).stream().filter(t->refs.contains(t.get("tableRef")))
                .filter(t->exact==null||exact.equals(t.get("tableName"))).map(LinkedHashMap::new).map(t->(Map<String,Object>)t).toList());
        });
    }
    @Test void defaultDiscoveryAndCompleteSchemaComeFromTheExistingScopedIndex(){
        var result=service.discover(args("DESCRIBE","tableName","ld_dataservice_api"),id->id.equals("owned"));
        assertThat(result).containsEntry("status","SUCCEEDED").containsEntry("sourceReadCount",0);
        assertThat(result.toString()).contains("OPENSEARCH","sql_content","schemaVersion");
        verify(search).indexedMetadata(eq("owned"),argThat(ids->ids.size()==1),isNull(),eq("ld_dataservice_api"));
        verify(search,never()).rankScopedMetadata(anyList(),any());verify(indexes,never()).refreshDatasource(any());
    }
    @Test void sqlExamplesReuseTheGovernedCatalogWithoutReadingAnotherDatasourceOrExecuting(){
        var catalog=mock(com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"templates",catalog);
        var owned=new com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService.BoundTemplate(
            "sql:owned@owned","sql:owned","service SQL","Stored service example","Owned","SQL",Map.of(),"r1","sql_query_execute",Map.of("datasourceId","owned"));
        var other=new com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService.BoundTemplate(
            "sql:other@other","sql:other","service SQL","Other source","Other","SQL",Map.of(),"r1","sql_query_execute",Map.of("datasourceId","other"));
        when(catalog.businessTemplates(false)).thenReturn(List.of(owned,other));
        when(catalog.readableContent("sql:owned")).thenReturn("select sql_content from ld_dataservice_api");
        var result=service.discover(args("SEARCH_SQL_EXAMPLES","query","sql_content"),id->id.equals("owned"));
        assertThat(result).containsEntry("status","SUCCEEDED").containsEntry("sourceReadCount",0);
        assertThat(result.toString()).contains("AUTHORIZED_TEMPLATE_ASSET_CATALOG","sql_content","r1").doesNotContain("Other source");
        verify(catalog,never()).readableContent("sql:other");verifyNoInteractions(indexes,search);
        when(catalog.businessTemplates(false)).thenReturn(List.of());
        assertThat(service.discover(args("SEARCH_SQL_EXAMPLES","query","service"),id->true).get("items")).isEqualTo(List.of());
    }
    @Test void staleIndexIsExplicitAndNeverTriggersAnUnrequestedSourceRead() throws Exception {
        var stale=new LinkedHashMap<>(service.indexEvidence(asset,index).get(0));stale.put("snapshotId","old-revision");
        when(search.indexedMetadata(any(),anyList(),any(),any())).thenReturn(Optional.of(List.of(stale)));
        assertThat(service.discover(args("DESCRIBE","tableName","ld_dataservice_api"),id->true)).containsEntry("status","INDEX_STALE");
        verify(indexes,never()).verifyTable(any(),any(),any(),any(),any());verify(indexes,never()).refreshDatasource(any());
    }
    @Test void missingForeignKeysReturnExplorableCandidatesRatherThanBusinessMetricFailure(){
        var result=service.discover(args("FIND_RELATED_TABLES","tableName","ld_dataservice_api"),id->true);
        assertThat(result.toString()).contains("ld_dataservice_group","project_id","CANDIDATE_FIELD_NAME_MATCH_NOT_VERIFIED_FOREIGN_KEY")
            .doesNotContain("INSUFFICIENT_BUSINESS_EVIDENCE");
    }
    @Test void freshAuthorizationFiltersCachedProjectionAndRejectsOldIndexedFields(){
        service.indexEvidence(asset,index);asset.setSensitiveFieldsJson("[\"sql_content\"]");
        assertThat(service.indexEvidence(asset,index).toString()).doesNotContain("name=sql_content");
        assertThatThrownBy(()->service.discover(args("DESCRIBE","tableName","ld_dataservice_api"),id->false)).isInstanceOf(SecurityException.class);
    }
    @Test void freshnessUsesTheCollectedRevisionWithoutRankingOrQueryingOpenSearch(){
        assertThat(service.discover(args("GET_METADATA_FRESHNESS"),id->true).toString()).contains("collectedAt=100","sourceUpdatedAt=UNKNOWN");
        verifyNoInteractions(search);verify(indexes,never()).refreshDatasource(any());
    }
    @Test void liveVerificationNeedsAnAuditableReasonAndIsLimitedToTheNamedObject() throws Exception {
        assertThatThrownBy(()->service.discover(args("VERIFY_LIVE_SCHEMA","tableName","ld_dataservice_api"),id->true)).isInstanceOf(IllegalArgumentException.class);
        when(indexes.verifyTable(asset,"db","db","ld_dataservice_api","INDEX_STALE")).thenReturn(index);
        var result=service.discover(args("VERIFY_LIVE_SCHEMA","database","db","schema","db","tableName","ld_dataservice_api","reason","INDEX_STALE"),id->true);
        assertThat(result).containsEntry("sourceReadCount",1).containsEntry("status","SUCCEEDED");
        assertThat(result.toString()).contains("JDBC_LIVE_METADATA","LIVE_STRUCTURE_VERIFIED","INDEX_STALE");
        verify(indexes,times(1)).verifyTable(asset,"db","db","ld_dataservice_api","INDEX_STALE");
        verify(indexes,never()).refreshDatasource(any());
    }
    private MetadataColumn field(TableLocation table,String name){return new MetadataColumn("owned","db","db",table.table(),name,"varchar",null,null,null,true,1);}
    private Map<String,Object> args(String operation,Object... pairs){var result=new LinkedHashMap<String,Object>();result.put("contractVersion",SqlMetadataDiscoveryService.CONTRACT);result.put("assetId","owned");result.put("operation",operation);for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1]);return result;}
}
