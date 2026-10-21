package com.chatchat.api.assets;

import com.chatchat.knowledgebase.search.document.infrastructure.extract.DocumentTextExtractor;
import com.chatchat.knowledgebase.search.index.infrastructure.opensearch.OpenSearchDocumentIndexService;
import com.chatchat.knowledgebase.search.index.model.LuceneSearchHit;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetKnowledgeServiceTest {
    final UserAssetService.Owner alice=new UserAssetService.Owner("tenant","alice"),bob=new UserAssetService.Owner("tenant","bob");
    JdbcTemplate jdbc;DataSourceTransactionManager tx;UserAssetService assets;AssetKnowledgeService knowledge;
    UserAssetServiceTest.MemoryBlobs blobs;OpenSearchDocumentIndexService index;
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:knowledge-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("assets/schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);tx=new DataSourceTransactionManager(db);blobs=new UserAssetServiceTest.MemoryBlobs();
        assets=new UserAssetService(jdbc,tx,blobs,new AssetStorageProperties());index=mock(OpenSearchDocumentIndexService.class);
        when(index.isAvailable()).thenReturn(true);knowledge=service();
    }
    AssetKnowledgeService service(){return new AssetKnowledgeService(jdbc,tx,assets,new DocumentTextExtractor(),index,new ObjectMapper());}
    UserAssetService.File upload(UserAssetService.Owner owner){return assets.upload(owner,"sales.csv","name,amount\nalpha,12.50".getBytes(StandardCharsets.UTF_8));}
    LuceneSearchHit hit(String id){return new LuceneSearchHit(id,"untrusted title","","text","chunk",0,"sales excerpt",0,1,"tenant","alice","private",List.of());}
    @Test void indexesExactOriginalWithExistingParserAndCanRebuildAfterRestart(){
        var file=upload(alice);assertThat(knowledge.status(alice,file.id()).state()).isEqualTo("PENDING");knowledge.drain();
        var doc=ArgumentCaptor.forClass(SearchDocument.class);verify(index).indexLatest(doc.capture());
        assertThat(doc.getValue().getContent()).contains("alpha,12.50");assertThat(doc.getValue().getVisibility()).isEqualTo("private");
        assertThat(doc.getValue().getSource()).endsWith(file.sha256());
        assertThat(service().status(alice,file.id()).state()).isEqualTo("INDEXED");
        assertThat(service().rebuild(alice)).isEqualTo(1);service().drain();verify(index,times(2)).indexLatest(any());
    }
    @Test void indexFailureIsDurableRedactedAndRetryableWithoutReupload(){
        var file=upload(alice);doThrow(new IllegalStateException("secret-token private data")).when(index).indexLatest(any());knowledge.drain();
        var state=service().status(alice,file.id());assertThat(state.state()).isEqualTo("FAILED");assertThat(state.errorCode()).isEqualTo("INDEX_WRITE_FAILED");
        assertThat(state.attempts()).isEqualTo(1);knowledge.drain();verify(index,times(1)).indexLatest(any());
        doNothing().when(index).indexLatest(any());service().retry(alice,file.id());service().drain();
        assertThat(knowledge.status(alice,file.id()).state()).isEqualTo("INDEXED");assertThat(assets.list(alice,null,null,100)).hasSize(1);
    }
    @Test void unavailableIndexNeverReportsSuccessAndUnsupportedFormatIsExplicit(){
        var file=upload(alice);when(index.isAvailable()).thenReturn(false);knowledge.drain();
        assertThat(knowledge.status(alice,file.id()).errorCode()).isEqualTo("INDEX_UNAVAILABLE");verify(index,never()).indexLatest(any());
        when(index.isAvailable()).thenReturn(true);
        var parquet=assets.upload(alice,"rows.parquet","PAR1PAR1".getBytes(StandardCharsets.US_ASCII));knowledge.process(parquet.id());
        assertThat(knowledge.status(alice,parquet.id()).state()).isEqualTo("SKIPPED");
    }
    @Test void emptyScopeNeverCallsIndexAndForeignHitsAreRejected(){
        assertThat(knowledge.search(bob,"sales",10,false)).isEmpty();verify(index,never()).search(anyString(),anyInt(),any(),anyList());
        var own=upload(alice);var foreign=upload(bob);knowledge.drain();
        when(index.search(anyString(),anyInt(),any(SearchPermissionContext.class),anyList())).thenReturn(List.of(hit(foreign.id()),hit(own.id())));
        var result=knowledge.search(alice,"sales",10,false);assertThat(result).extracting(AssetKnowledgeService.Hit::fileId).containsExactly(own.id());
        assertThat(result.get(0).fileName()).isEqualTo("sales.csv");
        var allowed=ArgumentCaptor.forClass(List.class);verify(index).search(eq("sales"),eq(10),any(),allowed.capture());
        assertThat(allowed.getValue()).containsExactly(own.id());
        assertThatThrownBy(()->knowledge.status(bob,own.id())).hasMessageContaining("404");
        assertThatThrownBy(()->knowledge.retry(bob,own.id())).hasMessageContaining("404");
    }
    @Test void deletionDuringSearchSuppressesSnippetsAndSurvivesIndexOutage(){
        var file=upload(alice);knowledge.drain();
        when(index.search(anyString(),anyInt(),any(SearchPermissionContext.class),anyList())).thenAnswer(call->{assets.delete(alice,file.id());return List.of(hit(file.id()));});
        assertThat(knowledge.search(alice,"sales",10,false)).isEmpty();
        when(index.isAvailable()).thenReturn(false);knowledge.process(file.id());
        assertThat(jdbc.queryForObject("select state from runtime_asset_knowledge_job where file_id=?",String.class,file.id())).isEqualTo("DELETE_PENDING");
        assertThatThrownBy(()->knowledge.retry(alice,file.id())).hasMessageContaining("404");
        when(index.isAvailable()).thenReturn(true);jdbc.update("update runtime_asset_knowledge_job set next_attempt_at=0 where file_id=?",file.id());service().drain();
        verify(index).deleteDocument(file.id());assertThat(jdbc.queryForObject("select state from runtime_asset_knowledge_job where file_id=?",String.class,file.id())).isEqualTo("DELETED");
    }
    @Test void historyProjectionExcludesRawEventsAndCarriesVerificationWarning(){
        var file=assets.store(alice,UserAssetService.Kind.CONTEXT,"task","run","user-analysis-result","context.json","application/json",
            "{\"instruction\":\"sales objective\",\"receipt\":{\"results\":[{\"reportMarkdown\":\"model report\"}],\"rawEvents\":\"PRIVATE_TRANSCRIPT\"}}".getBytes(StandardCharsets.UTF_8));
        knowledge.drain();var doc=ArgumentCaptor.forClass(SearchDocument.class);verify(index).indexLatest(doc.capture());
        assertThat(doc.getValue().getContent()).contains("verification must be checked","sales objective","model report").doesNotContain("PRIVATE_TRANSCRIPT");
        when(index.search(anyString(),anyInt(),any(SearchPermissionContext.class),anyList())).thenReturn(List.of(hit(file.id())));
        assertThat(knowledge.search(alice,"sales",10,true).get(0).evidenceStatus()).isEqualTo("HISTORICAL_MODEL_OUTPUT_REQUIRES_SOURCE_VALIDATION");
    }
    @Test void tamperedOriginalIsNotIndexed(){
        var file=upload(alice);blobs.corrupt=true;knowledge.drain();
        assertThat(knowledge.status(alice,file.id()).errorCode()).isEqualTo("OBJECT_READ_FAILED");verify(index,never()).indexLatest(any());
    }
}
