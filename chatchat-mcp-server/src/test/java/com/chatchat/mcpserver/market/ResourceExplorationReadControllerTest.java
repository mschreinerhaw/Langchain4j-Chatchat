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
