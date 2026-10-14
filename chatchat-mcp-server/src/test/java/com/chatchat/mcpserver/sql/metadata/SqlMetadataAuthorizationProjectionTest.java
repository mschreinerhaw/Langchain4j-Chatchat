package com.chatchat.mcpserver.sql.metadata;
import com.chatchat.mcpserver.sql.datasource.*;
import com.chatchat.mcpserver.sql.resolution.TableLocation;
import com.chatchat.mcpserver.search.engine.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SqlMetadataAuthorizationProjectionTest {
    @TempDir Path temp;
    @Test void filtersAssetsTablesAndFieldsBeforeRankCountsAndOutputIncludingCache() {
        var allowed=asset("a");var denied=asset("b");
        allowed.setAllowedTablesJson("[\"db.public_table\"]");
        allowed.setSensitiveFieldsJson("[\"DB.PUBLIC_TABLE.SECRET_VALUE\"]");
        var sources=mock(SqlDatasourceConfigService.class);
        when(sources.listEnabled()).thenReturn(List.of(allowed,denied));
        var indexes=mock(MetadataIndexService.class);
        var table=table("a","public_table");var hidden=table("a","hidden_table");
        when(indexes.allTables(allowed)).thenReturn(List.of(table,hidden));
        when(indexes.columns(allowed,table)).thenReturn(List.of(column("id"),column("secret_value")));
        var props=new LuceneSearchProperties();props.setIndexDir(temp.toString());
        var search=new SqlMetadataSearchService(new LuceneMcpSearchService(props),sources,indexes);
        var result=search.searchAuthorized(Map.of("query","public_table","detailLimit",1),id->id.equals("a"));
        assertThat(result.get("totalMatched")).isEqualTo(1);
        assertThat(result.toString()).contains("public_table").doesNotContain("secret_value","hidden_table");
        assertThat(search.searchAuthorized(Map.of("query","secret_value details"),id->id.equals("a")).get("totalMatched")).isEqualTo(0);
        assertThat(search.searchAuthorized(Map.of(),id->id.equals("a")).get("totalMatched")).isEqualTo(1);
        verify(indexes,never()).allTables(denied);verify(indexes,never()).columns(allowed,hidden);
    }
    @Test void deniedAndForgedAssetCannotLoadOrInitializeIndexes() {
        var sources=mock(SqlDatasourceConfigService.class);var indexes=mock(MetadataIndexService.class);
        when(sources.listEnabled()).thenReturn(List.of(asset("a"),asset("b")));
        var search=new SqlMetadataSearchService(mock(LuceneMcpSearchService.class),sources,indexes);
        assertThatThrownBy(()->search.searchAuthorized(Map.of("tableName","hidden_table"),id->false)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->search.searchAuthorized(Map.of("assetId","b","tenantId","admin","roles",List.of("admin")),id->id.equals("a"))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(indexes);
    }
    @Test void invalidAccessConfigurationFailsClosed() {
        var asset=asset("a");asset.setAllowedTablesJson("not-json");
        assertThatThrownBy(()->MetadataVisibility.tableVisible(asset,table("a","public_table"))).isInstanceOf(IllegalStateException.class);
        asset.setAllowedTablesJson("[]");asset.setSensitiveTablesJson("[\"DB.PUBLIC_TABLE\"]");
        assertThat(MetadataVisibility.tableVisible(asset,table("a","public_table"))).isFalse();
    }
    @Test void whitelistDoesNotExpandToAnotherCaseSensitivePhysicalObject() {
        var asset=asset("a");asset.setAllowedTablesJson("[\"db.Orders\"]");
        assertThat(MetadataVisibility.tableVisible(asset,table("a","Orders"))).isTrue();
        assertThat(MetadataVisibility.tableVisible(asset,table("a","orders"))).isFalse();
    }
    private SqlDatasourceConfig asset(String id) {var a=new SqlDatasourceConfig();a.setId(id);a.setName(id);a.setDatabaseType("mysql");return a;}
    private TableLocation table(String id,String name) {return new TableLocation(id,"db","db",name,"BASE TABLE",null,1);}
    private MetadataColumn column(String name) {return new MetadataColumn("a","db","db","public_table",name,"varchar",null,null,null,true,1);}
}
