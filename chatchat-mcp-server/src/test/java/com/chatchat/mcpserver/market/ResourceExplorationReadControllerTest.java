package com.chatchat.mcpserver.market;
import com.chatchat.runtime.market.storage.*;
import com.chatchat.mcpserver.news.runtime.NewsRuntimeClient;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResourceExplorationReadControllerTest {
    @Test void sourceEmptyRequiresActualBoundedReadsOfBothRetainedTiers() throws Exception {
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:empty_p0_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        var real=new FinancialDataStore(jdbc,ds,json,new com.chatchat.runtime.market.config.MarketModuleProperties());real.initialize();
        var source=new com.chatchat.runtime.market.model.MarketSource(6L,"empty_p0_fixture","Isolated fixture","https://example.test");
        real.storeAll(List.of(new com.chatchat.runtime.market.model.MarketObservation(source,"fixture","quote",null,"SSE",
            "https://example.test/quote",java.time.Instant.parse("2026-07-23T08:00:00Z"),"zh-CN",List.of("fixture"),List.of("SSE"),
            Map.of("datasetCode","market_quote_daily","tradeDate","2026-07-23","quoteCode","000001","quoteName","Fixture","close","10.5"))));
        assertThat(real.validDatasetCoverage("market_quote_daily")).containsEntry("empty",false);
        // Only the isolated in-memory fixture is emptied, never the financial deployment.
        jdbc.update("delete from market_quote_daily");jdbc.update("update market_asset_catalog set last_observation_date=null where dataset_code='market_quote_daily'");
        var governed=new ResourceExplorationReadController(news,catalog,real,json);
        var args=Map.of("dataset","market_quote_daily","start_date","2026-07-23","end_date","2026-07-23","history_mode","daily","limit",1);
        var result=governed.read(json.valueToTree(Map.of("tool","market_get_history","arguments",args))).getData();
        assertThat(result).containsEntry("count",0);
        var coverage=(Map<?,?>)result.get("source_coverage");
        assertThat(coverage.get("empty")).isEqualTo(true);assertThat(coverage.get("readModes")).isEqualTo(List.of("daily","weekly"));
        assertThat(coverage.get("maxRowsPerRead")).isEqualTo(1);
        if(System.getProperty("runtime.empty.census")!=null)java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("runtime.empty.census")),
            json.writeValueAsString(Map.of("tool","market_get_history","query",args,"response",result,"evidenceScope","ISOLATED_H2_REAL_STORAGE_AND_MCP_CONTROLLER")));
        assertThatThrownBy(()->real.validDatasetCoverage("not_a_registered_dataset")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void dailySnapshotIsBoundedCompleteAndCannotReturnRestrictedRowsOrAcceptRawSql() throws Exception {
        var day=java.time.LocalDate.parse("2026-11-05");
        when(store.catalog("quotes")).thenReturn(Map.of("dataset_code","quotes","table_name","private_table"));
        when(store.daySnapshot("quotes",day,6L)).thenReturn(Map.of("complete",true,"rows",List.of(Map.of("quote_code","300608","payload_json","raw")),"coverage","stored source/day"));
        var result=read("market_get_day_snapshot",Map.of("dataset","quotes","trade_date","2026-11-05","source_id",6));
        assertThat(result).containsEntry("complete",true).containsEntry("delivery","IMMUTABLE_DAY_FILE");
        assertThat(json.writeValueAsString(result)).doesNotContain("private_table","payload_json");
        assertThatThrownBy(()->read("market_get_day_snapshot",Map.of("dataset","quotes","trade_date","2026-11-05","source_id",6,"sql","select *"))).isInstanceOf(IllegalArgumentException.class);
        when(store.daySnapshot("quotes",day,6L)).thenReturn(Map.of("complete",true,"rows",List.of(Map.of("legal_risk",true))));
        assertThatThrownBy(()->read("market_get_day_snapshot",Map.of("dataset","quotes","trade_date","2026-11-05","source_id",6))).isInstanceOf(SecurityException.class);
    }
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final NewsRuntimeClient news=mock(NewsRuntimeClient.class);
    final FinancialAssetCatalogService catalog=mock(FinancialAssetCatalogService.class);
    final FinancialDataStore store=mock(FinancialDataStore.class);
    final ResourceExplorationReadController api=new ResourceExplorationReadController(news,catalog,store,json);
    Map<String,Object> read(String tool,Map<String,Object> args) throws Exception{return api.read(json.valueToTree(Map.of("tool",tool,"arguments",args))).getData();}
    @Test void existingStoreOwnsHistoryReadAndNoPhysicalStorageOrRawPayloadIsDisclosed() throws Exception {
        when(store.query(eq("quotes"),anyMap(),any(),any(),eq(200),eq("auto"))).thenReturn(Map.of("rows",List.of(
            Map.of("observation_date","2026-11-03","price",10,"payload_json","private raw","source_url","https://example.org/market")),"history_mode","auto","storage_tiers",List.of("daily_hot")));
        when(store.catalog("quotes")).thenReturn(Map.of("dataset_code","quotes","asset_name","Prices","table_name","secret_table","last_observation_date","2026-11-03","fields",List.of(Map.of("field_name","legal_risk"))));
        var result=read("market_get_snapshot",Map.of("dataset","quotes","limit",1));
        assertThat(result.get("realtime")).isEqualTo(false);
        assertThat(json.writeValueAsString(result)).contains("2026-11-03","price").doesNotContain("private raw","secret_table","payload_json");
        verify(store).query(eq("quotes"),argThat(filters->Boolean.FALSE.equals(filters.get("legal_risk"))),any(),any(),eq(200),eq("auto"));
        verifyNoInteractions(news,catalog);
    }
    @Test void newsDelegatesThroughExistingSignedClientRatherThanANewLocalEngine() throws Exception {
        when(news.post(eq("/resource-exploration"),any())).thenReturn(json.valueToTree(Map.of("items",List.of())));
        assertThat(read("news_search",Map.of("query","banking"))).containsKey("items");verifyNoInteractions(store,catalog);
    }
    @Test void unboundedAndIdentityInputsAreRejectedBeforeAnyDataRead() {
        assertThatThrownBy(()->read("market_get_history",Map.of("dataset","quotes","limit",9999))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->read("market_get_history",Map.of("dataset","quotes","sql","select * from secrets"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->read("market_search",Map.of("index","*"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(news,catalog,store);
    }
    @Test void mixedValidationBatchUsesOneSignedNewsBatchAndPreservesSourceOrder() throws Exception {
        when(news.post(eq("/resource-exploration/batch"),any())).thenReturn(json.valueToTree(List.of(Map.of("article_id","n1"),Map.of("article_id","n2"))));
        when(store.catalog("quote")).thenReturn(Map.of("dataset_code","quote","asset_name","Prices"));
        var result=api.readBatch(json.valueToTree(Map.of("requests",List.of(
            Map.of("tool","news_get_article","arguments",Map.of("article_id","n1","version_id","v1")),
            Map.of("tool","market_get_dataset","arguments",Map.of("dataset","quote")),
            Map.of("tool","news_get_article","arguments",Map.of("article_id","n2","version_id","v2")))))).getData();
        assertThat(result.get(0)).containsEntry("article_id","n1");assertThat(result.get(1)).containsEntry("dataset_code","quote");assertThat(result.get(2)).containsEntry("article_id","n2");
        verify(news,times(1)).post(eq("/resource-exploration/batch"),any());
        assertThatThrownBy(()->api.readBatch(json.valueToTree(Map.of("requests",List.of(Map.of("tool","market_search","arguments",Map.of())))))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void batchRejectsIdentityOnAnyItemBeforeDelegatingAnySource() {
        var request=json.valueToTree(Map.of("requests",List.of(
            Map.of("tool","news_get_article","arguments",Map.of("article_id","n1","version_id","v1")),
            Map.of("tool","market_get_dataset","arguments",Map.of("dataset","quotes","user_id","other")))));
        assertThatThrownBy(()->api.readBatch(request)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(news,catalog,store);
    }
}
