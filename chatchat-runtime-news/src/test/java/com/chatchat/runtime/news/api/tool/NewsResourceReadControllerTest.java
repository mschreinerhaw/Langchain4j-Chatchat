package com.chatchat.runtime.news.api.tool;
import com.chatchat.runtime.news.model.*;
import com.chatchat.runtime.news.store.NewsDocumentStore;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NewsResourceReadControllerTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final NewsDocumentStore store=mock(NewsDocumentStore.class);
    final NewsResourceReadController api=new NewsResourceReadController(store,json);
    NewsDocument article(boolean restricted) {return new NewsDocument("n1",1L,"Feed",null,"Announcement","Original article. ".repeat(100),"Published summary.",null,
        "https://example.org/article",Instant.parse("2026-11-02T01:00:00Z"),Instant.parse("2026-11-03T01:00:00Z"),"zh",List.of("banking"),List.of(),"hash",null,Map.of("legalRisk",restricted,"securityCode","600001"));}
    Map<String,Object> read(String tool,Map<String,Object> args) throws Exception{return api.read(json.valueToTree(Map.of("tool",tool,"arguments",args))).getData();}
    @Test void delegatesPublicationSecurityAndIndustryFiltersAndSupportsBoundedDeepReading() throws Exception {
        when(store.search(any())).thenReturn(List.of(article(false)));when(store.findById("n1")).thenReturn(Optional.of(article(false)));
        var result=read("news_search",Map.of("query","policy","security_code","600001","industry","banking","start_time","2026-11-01T00:00:00Z","limit",1));
        verify(store).search(argThat(q->q.governedRead()&&q.securityCode().equals("600001")&&q.categories().isEmpty()&&q.industry().equals("banking")&&q.startTime().equals(Instant.parse("2026-11-01T00:00:00Z"))));
        var item=(Map<?,?>)((List<?>)result.get("items")).get(0);
        assertThat(item.get("published_at")).isNotEqualTo(item.get("collected_at"));
        var body=read("news_get_article",Map.of("article_id","n1","version_id",item.get("version_id"),"limit",200,"offset",0));
        assertThat(body.get("text").toString()).hasSize(200);assertThat(body.get("next_offset")).isEqualTo(200);
        assertThat(body.get("source_view")).isEqualTo("body");
    }
    @Test void unknownDslIdentityAndInvalidDatesAreRejectedBeforeStoreAccess() {
        assertThatThrownBy(()->read("news_search",Map.of("index","*","query","x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->read("news_search",Map.of("tenant_id","other"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->read("news_search",Map.of("start_time","2026-11-03T00:00:00Z","end_time","2026-11-02T00:00:00Z"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }
    @Test void restrictedSourceAndChangedVersionsCannotBeRead() throws Exception {
        when(store.findById("n1")).thenReturn(Optional.of(article(true)));
        assertThatThrownBy(()->read("news_get_article",Map.of("article_id","n1","version_id","x"))).isInstanceOf(SecurityException.class);
        when(store.findById("n1")).thenReturn(Optional.of(article(false)));
        assertThatThrownBy(()->read("news_get_article",Map.of("article_id","n1","version_id","wrong"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version changed");
    }
    @Test void articleBatchRetainsVersionComplianceAndOperationChecks() throws Exception {
        when(store.search(any())).thenReturn(List.of(article(false)));when(store.findById("n1")).thenReturn(Optional.of(article(false)));
        var item=(Map<?,?>)((List<?>)read("news_search",Map.of("query","policy")).get("items")).get(0);
        var request=json.valueToTree(Map.of("requests",List.of(Map.of("tool","news_get_article","arguments",Map.of("article_id","n1","version_id",item.get("version_id"),"view","summary")))));
        assertThat(api.readBatch(request).getData()).singleElement().satisfies(source->assertThat(source).containsEntry("source_view","summary"));
        when(store.findById("n1")).thenReturn(Optional.of(article(true)));
        assertThatThrownBy(()->api.readBatch(request)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->api.readBatch(json.valueToTree(Map.of("requests",List.of(Map.of("tool","news_search","arguments",Map.of())))))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void articleBatchRejectsIdentityBeforeReadingAnyArticle() {
        var request=json.valueToTree(Map.of("requests",List.of(
            Map.of("tool","news_get_article","arguments",Map.of("article_id","n1","version_id","v1")),
            Map.of("tool","news_get_article","arguments",Map.of("article_id","n2","version_id","v2","tenant_id","other")))));
        assertThatThrownBy(()->api.readBatch(request)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(store);
    }
}
