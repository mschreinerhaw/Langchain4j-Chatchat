package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.*;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.index.application.PerDocumentIndexService;
import com.chatchat.knowledgebase.search.index.infrastructure.opensearch.OpenSearchDocumentIndexService;
import com.chatchat.knowledgebase.search.index.model.LuceneSearchHit;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.query.application.TextChunker;
import com.chatchat.knowledgebase.search.service.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingKnowledgeServiceTest {
    @Test void actualKnowledgeEntryAcceptsEngineSelectionWithoutAcceptingIdentityArguments(){
        resourceTask("web_search","web_fetch");resourceGrants.set(Set.of("internet_search"));
        var internet=mock(CodingInternetAccess.class);knowledge.setInternet(internet);
        when(internet.read(any(),eq("web_search"),anyString(),any())).thenAnswer(call->{
            assertThat(call.getArgument(3,com.fasterxml.jackson.databind.JsonNode.class).path("provider_id").asText()).isEqualTo("selected-engine");
            return new LinkedHashMap<String,Object>(Map.of("status","OK","items",List.of(),"count",0));
        });
        when(internet.read(any(),eq("web_fetch"),anyString(),any())).thenReturn(new LinkedHashMap<>(Map.of("status","ERROR","errorCode","PAGE_NOT_AVAILABLE","count",0)));
        assertThat(call("web_search",Map.of("query","public","provider_id","selected-engine"))).containsEntry("status","OK");
        assertThat(call("web_fetch",Map.of("url","https://example.com","provider_id","selected-engine"))).containsEntry("errorCode","PAGE_NOT_AVAILABLE");
        assertThatThrownBy(()->call("web_search",Map.of("query","public","provider_id","selected-engine","user_id","other"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void privateCheckpointRetainsContractFencingWhileExpiredSourcesStillBlockModelAdmission() {
        var guard=mock(CodingKnowledgeService.class);
        doThrow(new SecurityException("Source changed")).when(guard).validateContext(any());
        var controller=new com.chatchat.api.runtime.coding.cline.ClineModelCallbackController(identity,tasks,null,null,null,null,null,json);
        controller.setKnowledge(guard);
        String hash=CodingCanonicalHash.sha256(contract);
        var body=json.createObjectNode();body.set("scope",json.valueToTree(scope));body.put("taskId","task");
        body.put("contractHash",hash);body.put("fencingToken",lease.fencingToken());body.set("contract",json.valueToTree(contract));
        body.set("checkpoint",json.valueToTree(Map.of("inputSnapshotRef",contract.inputSnapshotRef(),"workspaceRef",contract.workspaceRef(),
            "contractHash",hash,"modelOrdinal",0,"eventOrdinal",0,"checkpointOrdinal",1,"sdkSessionId","private-checkpoint",
            "messages",List.of(Map.of("role","user","content",List.of(Map.of("type","text","text","private transcript")))))));
        assertThat(controller.checkpoint(body)).containsEntry("saved",true);verifyNoInteractions(guard);
        body.set("model",json.valueToTree(Map.of("operationId","model-1","modelCalls",1,"maxTokens",1024)));
        assertThatThrownBy(()->controller.reserve(body)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("not authorized");
        verify(guard).validateContext(any());
        var forged=body.deepCopy();forged.put("fencingToken",lease.fencingToken()+1);
        assertThatThrownBy(()->controller.checkpoint(forged)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void priorResourceProvenanceIsScopedToExactConversationTenantAndUserWithoutWildcardBypass() {
        var previous=new KernelDataScope("tenant","owner",null,"c%_!1","previous-run",null,Map.of());var payload=Map.<String,Object>of("taskId","previous");
        evidence.register(new EvidenceRecord(null,"knowledge:previous",previous,"RESOURCE_NEWS_REFERENCE","test",evidence.contentHash(payload),payload,null,0,Map.of()),
            new EvidenceLineage(null,"knowledge:previous",previous,List.of(),"previous","test",null,Map.of()));
        var guard=new KnowledgeDisclosureGuard(jdbc,json,identity,tasks,evidence,knowledge);
        assertThat(guard.hasResourceHistory("tenant","owner","c%_!1")).isTrue();
        assertThat(guard.hasResourceHistory("tenant","owner","c")).isFalse();
        assertThat(guard.hasResourceHistory("tenant","other","c%_!1")).isFalse();
        assertThat(guard.hasResourceHistory("other","owner","c%_!1")).isFalse();
    }
    java.util.concurrent.atomic.AtomicReference<Set<String>> resourceGrants;
    CodingResourceReader reader;
    void resourceTask(String... tools) {
        var node=json.valueToTree(contract);((com.fasterxml.jackson.databind.node.ObjectNode)node).put("taskId","resource-task");
        ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("authorization")).set("allowedTools",json.valueToTree(java.util.stream.Stream.concat(Arrays.stream(tools),java.util.stream.Stream.of("report_submit")).toList()));
        contract=json.convertValue(node,CodingTaskContract.class);as(()->tasks.submit(contract));
        lease=as(()->tasks.claim(scope,contract.taskId(),"cline-adapter:test",Duration.ofMinutes(5),tasks.get(scope,contract.taskId()).revision()));
        resourceGrants=new java.util.concurrent.atomic.AtomicReference<>(Set.of("financial_news","financial_market"));
        knowledge.setResourceScopes((t,u,a,approved)->resourceGrants.get());reader=mock(CodingResourceReader.class);knowledge.setResourceReader(reader);
        when(reader.readBatch(anyList())).thenAnswer(call->call.<List<CodingResourceReader.Read>>getArgument(0).stream()
            .map(read->reader.read(read.tool(),read.arguments())).toList());
    }
    @Test void emptyReadRetainsExactQueryAndHistoryWithoutEndingTaskOrRetryingByItself() {
        resourceTask("market_get_history","market_resolve_security");
        when(reader.read(eq("market_get_history"),any())).thenAnswer(x->new LinkedHashMap<>(Map.of("dataset","quotes","count",0,"items",List.of())));
        when(reader.read(eq("market_resolve_security"),any())).thenAnswer(x->new LinkedHashMap<>(Map.of("items",List.of(),"count",0)));
        var query=Map.<String,Object>of("dataset","quotes","start_date","2026-11-02","end_date","2026-11-02","filters",Map.of("quote_code","SZSE:301218"));
        var first=call("market_get_history",query);
        var observation=(Map<?,?>)first.get("dataObservation");
        assertThat(observation.get("status")).isEqualTo("EMPTY_RESULT");assertThat(observation.get("query")).isEqualTo(query);
        assertThat(observation.get("sourceEmptyProven")).isEqualTo(false);assertThat(observation.get("startedAt")).isNotNull();
        assertThat(as(()->tasks.get(scope,contract.taskId())).status().terminal()).isFalse();
        var next=call("market_resolve_security",Map.of("query","301218"));
        var paths=(List<?>)((Map<?,?>)next.get("dataObservation")).get("attemptedPaths");
        assertThat(paths).hasSize(1);assertThat(paths.get(0).toString()).contains("market_get_history","EMPTY_RESULT","runtime-evidence:knowledge-tool:");
        assertThat(as(()->tasks.privateState(scope,contract.taskId(),"data-exploration-observations")).size()).isEqualTo(2);
    }
    @Test void sourceUnavailableIsNotZeroRowsAndHasAnImmutableScopedFailureReceipt() {
        resourceTask("market_get_history");
        when(reader.read(eq("market_get_history"),any())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"financial source timed out"));
        var args=json.valueToTree(Map.of("dataset","quotes"));String callId="timeout";
        assertThatThrownBy(()->as(()->knowledge.call(contract,lease,"market_get_history",callId,args)))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("timed out");
        String operation="workspace-"+com.chatchat.agents.protocol.ModelProtocolJson.sha256Hex(contract.taskId()+"\0market_get_history\0"+callId);
        var receipt=as(()->evidence.find(scope,"knowledge-tool:"+operation).orElseThrow());
        var state=(Map<?,?>)receipt.payload().get("dataObservation");
        assertThat(state.get("status")).isEqualTo("SOURCE_UNAVAILABLE");assertThat(state.get("returnedRowCount")).isNull();
        assertThat(receipt.payload().get("failureMessage").toString()).contains("financial source timed out");
        assertThat(as(()->tasks.unresolvedOperations(scope,contract.taskId()))).isEmpty();
    }
    @Test void deniedReadHasNoRemoteDispatchAndRetainsTheDeniedQueryInScopedEvidence() {
        resourceTask("market_get_history");resourceGrants.set(Set.of());
        var args=json.valueToTree(Map.of("dataset","quotes"));String callId="denied";
        assertThatThrownBy(()->as(()->knowledge.call(contract,lease,"market_get_history",callId,args))).isInstanceOf(SecurityException.class);
        String id="resource-denial:"+com.chatchat.agents.protocol.ModelProtocolJson.sha256Hex(contract.taskId()+"\0market_get_history\0"+callId+"\0"+args);
        var receipt=as(()->evidence.find(scope,id).orElseThrow());
        assertThat(receipt.payload()).containsEntry("remoteRequestSent",false);
        var state=(Map<?,?>)receipt.payload().get("dataObservation");assertThat(state.get("status")).isEqualTo("ACCESS_DENIED");
        assertThat(state.get("returnedRowCount")).isNull();verifyNoInteractions(reader);
    }
    Map<String,Object> newsBody(String text) {return Map.of("article_id","n1","version_id","v1","title","Policy announcement","text",text,
        "source_url","https://example.org/article","published_at","2026-11-02T01:00:00Z","collected_at","2026-11-03T01:00:00Z","source_view","body","offset",0);}
    @Test void typedParquetChunkInObservationMustRoundTripWithoutLosingItsStopProof() {
        resourceTask("market_get_history");
        var chunk=new com.chatchat.api.runtime.coding.data.CodingInputSnapshotService.Chunk("part-id","part-00000.parquet","a".repeat(64),0,128);
        var body=Map.<String,Object>of("dataset","quotes","items",List.of(),"count",0,
            "calculationInput",Map.of("descriptor",Map.of("chunks",List.of(chunk))));
        when(reader.read(eq("market_get_history"),any())).thenAnswer(x->new LinkedHashMap<>(body));
        var value=call("market_get_history",Map.of("dataset","quotes"));
        assertThat(((Map<?,?>)value.get("dataObservation")).get("status")).isEqualTo("EMPTY_RESULT");
        assertThat(as(()->tasks.unresolvedOperations(scope,contract.taskId()))).isEmpty();
        var entries=jdbc.queryForList("select entry_id from runtime_evidence_entry where evidence_type='CODING_RESOURCE_READ_RECEIPT'");
        assertThat(entries).hasSize(1);
        var record=as(()->evidence.find(scope,entries.get(0).get("ENTRY_ID").toString()).orElseThrow());
        assertThat(evidence.contentHash(record.payload())).isEqualTo(record.contentSha256());
    }
    @Test void legacyTypedReceiptRequiresItsExactOriginalHashAndCanCloseWithoutReplayingARead() {
        resourceTask("market_get_history");String operation="workspace-"+"b".repeat(64),handle="runtime-workspace:"+operation;
        var chunk=new com.chatchat.api.runtime.coding.data.CodingInputSnapshotService.Chunk("part-id","part-00000.parquet","a".repeat(64),0,128);
        var payload=new LinkedHashMap<String,Object>();payload.put("taskId",contract.taskId());payload.put("operationId",operation);
        payload.put("executionHandle",handle);payload.put("executorStopped",true);payload.put("executorScope","RUNTIME_HTTP_CLIENT");payload.put("remoteReadOnly",true);
        payload.put("dataObservation",Map.of("schemaVersion",CodingDataStatus.VERSION,"calculationInput",Map.of("descriptor",Map.of("chunks",List.of(chunk)))));
        String id="knowledge-tool:"+operation,original=evidence.contentHash(payload);
        as(()->evidence.register(new EvidenceRecord(null,id,scope,"CODING_RESOURCE_READ_RECEIPT","CodingKnowledgeService",original,payload,null,0,Map.of()),
            new EvidenceLineage(null,id,scope,List.of(),contract.taskId(),"CodingKnowledgeService",null,Map.of())));
        var stored=as(()->evidence.find(scope,id).orElseThrow());
        assertThat(evidence.contentHash(stored.payload())).isNotEqualTo(original);
        assertThat(evidence.contentHashInPersistedOrder(stored.payload())).isEqualTo(original);
        var verifier=new com.chatchat.api.runtime.coding.workspace.CodingWorkspaceProofVerifier(evidence);
        assertThat(verifier.confirmsStopped(scope,contract.taskId(),operation,handle,"runtime-evidence:"+id)).isTrue();
        assertThat(verifier.confirmsStopped(scope,"other-task",operation,handle,"runtime-evidence:"+id)).isFalse();
        assertThat(verifier.confirmsStopped(scope,contract.taskId(),operation,"other-handle","runtime-evidence:"+id)).isFalse();
        as(()->evidence.register(new EvidenceRecord(null,id+":other",scope,"CODING_RESOURCE_READ_RECEIPT","OtherProducer",original,payload,null,0,Map.of()),
            new EvidenceLineage(null,id+":other",scope,List.of(),contract.taskId(),"OtherProducer",null,Map.of())));
        assertThat(verifier.confirmsStopped(scope,contract.taskId(),operation,handle,"runtime-evidence:"+id+":other")).isFalse();
        as(()->tasks.reserve(scope,contract.taskId(),lease,tasks.get(scope,contract.taskId()).revision(),operation,"c".repeat(64),
            CodingJournal.OperationKind.TOOL,new CodingTaskOutcome.Counters(0,0,1,0,0,0,160000,"0")));
        as(()->tasks.startIntent(scope,contract.taskId(),lease,tasks.get(scope,contract.taskId()).revision(),operation,handle));
        as(()->tasks.reconcileStopped(scope,contract.taskId(),lease,tasks.get(scope,contract.taskId()).revision(),operation,"runtime-evidence:"+id));
        as(()->tasks.settle(scope,contract.taskId(),lease,tasks.get(scope,contract.taskId()).revision(),operation,
            new CodingTaskOutcome.Counters(0,0,0,0,0,0,0,"0"),false));
        assertThat(as(()->tasks.unresolvedOperations(scope,contract.taskId()))).isEmpty();verifyNoInteractions(reader);
        assertThat(as(()->evidence.find(scope,id).orElseThrow()).contentSha256()).isEqualTo(original);
        assertThat(as(()->tasks.get(scope,contract.taskId())).usage().meteringComplete()).isFalse();
    }
    @Test void resourceCallbacksCannotExpandTaskToolsOrLiveResourceGrants() {
        resourceTask("news_get_article");
        assertThat(knowledge.allowedTools("tenant","owner","agent",true)).contains("news_search","market_search").doesNotContain("knowledge_search");
        assertThatThrownBy(()->call("market_search",Map.of("query","stocks"))).isInstanceOf(SecurityException.class);
        resourceGrants.set(Set.of());
        assertThatThrownBy(()->call("news_get_article",Map.of("article_id","n1","version_id","v1"))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(reader);
    }
    @Test void newsEvidenceRemainsLiveAcrossCallbacksReportsAndHistoryReads() {
        resourceTask("news_get_article");
        when(reader.read(eq("news_get_article"),any())).thenAnswer(call->new LinkedHashMap<>(newsBody("Original announced policy, not a proven price cause.")));
        var result=call("news_get_article",Map.of("article_id","n1","version_id","v1"));String ref=(String)result.get("evidence_id");
        var source=as(()->knowledge.source(contract,ref));assertThat(source.get("published_at")).isEqualTo("2026-11-02T01:00:00Z");
        assertThat(source.get("page")).isNull();assertThat(source.get("text")).isEqualTo("Original announced policy, not a proven price cause.");
        var guard=new KnowledgeDisclosureGuard(jdbc,json,identity,tasks,evidence,knowledge);
        guard.checkRun("tenant","owner","run");
        resourceGrants.set(Set.of("financial_market"));
        assertThatThrownBy(()->as(()->knowledge.source(contract,ref))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->guard.checkRun("tenant","owner","run")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->as(()->{knowledge.validateContext(contract);return null;})).isInstanceOf(SecurityException.class);
    }
    @Test void sameVersionChangedArticleCannotReuseOldEvidence() {
        resourceTask("news_get_article");when(reader.read(eq("news_get_article"),any())).thenAnswer(call->new LinkedHashMap<>(newsBody("original")));
        var result=call("news_get_article",Map.of("article_id","n1","version_id","v1"));
        when(reader.read(eq("news_get_article"),any())).thenAnswer(call->new LinkedHashMap<>(newsBody("updated")));
        assertThatThrownBy(()->as(()->knowledge.source(contract,(String)result.get("evidence_id")))).isInstanceOf(SecurityException.class).hasMessageContaining("changed");
    }
    @Test void batchValidationIsFreshPerPassAndRevocationDuringIoCannotDiscloseRetainedContent() {
        resourceTask("news_get_article");when(reader.read(eq("news_get_article"),any())).thenAnswer(call->new LinkedHashMap<>(newsBody("original")));
        call("news_get_article",Map.of("article_id","n1","version_id","v1"));clearInvocations(reader);
        as(()->{knowledge.validateContext(contract);knowledge.validateContext(contract);return null;});
        verify(reader,times(2)).readBatch(anyList());
        when(reader.readBatch(anyList())).thenAnswer(call->{resourceGrants.set(Set.of());return List.of(new LinkedHashMap<>(newsBody("original")));});
        assertThatThrownBy(()->as(()->{knowledge.validateContext(contract);return null;})).isInstanceOf(SecurityException.class);
    }
    @Test void financialValidationDoesNotReadUnrelatedBoundDocumentOriginals() {
        resourceTask("news_get_article");when(reader.read(eq("news_get_article"),any())).thenAnswer(call->new LinkedHashMap<>(newsBody("original")));
        clearInvocations(originals);
        var result=call("news_get_article",Map.of("article_id","n1","version_id","v1"));
        as(()->{knowledge.validateContext(contract);knowledge.source(contract,(String)result.get("evidence_id"));return null;});
        verifyNoInteractions(originals);
    }
    @Test void validationUsesFreshAuthorizationBeforeAndAfterBatchWithoutRepeatingGraphQueriesPerCitation() {
        resourceTask("news_get_article");
        when(reader.read(eq("news_get_article"),any())).thenAnswer(call->{
            var body=new LinkedHashMap<>(newsBody("original"));body.put("article_id",call.<com.fasterxml.jackson.databind.JsonNode>getArgument(1).path("article_id").asText());return body;
        });
        call("news_get_article",Map.of("article_id","n1","version_id","v1"));
        call("news_get_article",Map.of("article_id","n2","version_id","v1"));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        knowledge.setResourceScopes((t,u,a,approved)->{count.incrementAndGet();return resourceGrants.get();});
        as(()->{knowledge.validateContext(contract);return null;});
        assertThat(count.get()).isEqualTo(3); // live profile plus fresh batch admission and release checks
        as(()->{knowledge.validateContext(contract);return null;});
        assertThat(count.get()).isEqualTo(6); // no authorization survives the next validation boundary
    }
    @Test void financialEvidenceUsesActualRowsAndInvalidatesChangedSnapshots() {
        resourceTask("market_get_history");var rows=new java.util.concurrent.atomic.AtomicReference<>(List.of(Map.<String,Object>of("observation_date","2026-11-02","price",12)));
        when(reader.read(eq("market_get_history"),any())).thenAnswer(call->new LinkedHashMap<>(Map.of("dataset","quote","items",rows.get(),"asset",Map.of("asset_name","Collected prices","last_observation_date","2026-11-02"),"realtime",false)));
        var result=call("market_get_history",Map.of("dataset","quote"));String ref=(String)result.get("evidence_id");
        assertThat(as(()->knowledge.source(contract,ref)).get("text").toString()).contains("2026-11-02","price");
        rows.set(List.of(Map.of("observation_date","2026-11-02","price",99)));
        assertThatThrownBy(()->as(()->knowledge.source(contract,ref))).isInstanceOf(SecurityException.class);
    }
    @Test void catalogHeartbeatRefreshPreservesObservedProvenanceButChangedMetadataStillExpires() {
        resourceTask("market_search");var heartbeat=new java.util.concurrent.atomic.AtomicReference<>("2026-11-03T01:00:00Z");
        when(reader.read(eq("market_search"),any())).thenAnswer(call->new LinkedHashMap<>(Map.of("items",List.of(Map.of("dataset_code","quote")))));
        when(reader.read(eq("market_get_dataset"),any())).thenAnswer(call->new LinkedHashMap<>(Map.of("dataset_code","quote","asset_name","Prices","last_collected_at",heartbeat.get())));
        var result=call("market_search",Map.of("query","prices"));
        @SuppressWarnings("unchecked") var item=((List<Map<String,Object>>)result.get("items")).get(0);String ref=(String)item.get("evidence_id");
        heartbeat.set("2026-11-03T01:05:00Z");
        var source=as(()->knowledge.source(contract,ref));assertThat(source.get("catalog_collected_at")).isEqualTo("2026-11-03T01:00:00Z");
        assertThat(source.get("text").toString()).contains("2026-11-03T01:00:00Z","heartbeat").doesNotContain("2026-11-03T01:05:00Z");
        when(reader.read(eq("market_get_dataset"),any())).thenReturn(Map.of("dataset_code","quote","asset_name","Changed source"));
        assertThatThrownBy(()->as(()->knowledge.source(contract,ref))).isInstanceOf(SecurityException.class);
    }
    @Test void observationTimestampsRemainVersionedWhileCatalogHeartbeatCanAdvance() {
        resourceTask("market_get_snapshot");var heartbeat=new java.util.concurrent.atomic.AtomicReference<>("2026-11-03T01:00:00Z");
        var collected=new java.util.concurrent.atomic.AtomicReference<>("2026-11-02T10:00:00Z");
        when(reader.read(eq("market_get_snapshot"),any())).thenAnswer(call->new LinkedHashMap<>(Map.of("dataset","quote","data_as_of","2026-11-02",
            "asset",Map.of("asset_name","Prices","last_observation_date","2026-11-03","last_collected_at",heartbeat.get()),
            "items",List.of(Map.of("price",12,"collected_at",collected.get())))));
        String ref=(String)call("market_get_snapshot",Map.of("dataset","quote")).get("evidence_id");heartbeat.set("2026-11-03T01:05:00Z");
        var source=as(()->knowledge.source(contract,ref));assertThat(source).containsEntry("collected_at","2026-11-02T10:00:00Z").containsEntry("source_version","2026-11-02");
        collected.set("2026-11-02T10:01:00Z");
        assertThatThrownBy(()->as(()->knowledge.source(contract,ref))).isInstanceOf(SecurityException.class);
    }
    @Test void searchEvidenceUsesCurrentStoreMetadataRatherThanTheCatalogIndexRepresentation() {
        resourceTask("market_search");
        when(reader.read(eq("market_search"),any())).thenAnswer(call->new LinkedHashMap<>(Map.of("items",List.of(Map.of(
            "dataset_code","quote","asset_name","Indexed catalog title","fields",List.of(Map.of("field_name","price")))))));
        when(reader.read(eq("market_get_dataset"),any())).thenAnswer(call->new LinkedHashMap<>(Map.of(
            "dataset_code","quote","asset_name","Current collected prices","fields","[{\"field_name\":\"price\"}]")));
        var result=call("market_search",Map.of("query","prices"));
        @SuppressWarnings("unchecked") var item=((List<Map<String,Object>>)result.get("items")).get(0);
        assertThat(item).containsEntry("asset_name","Current collected prices");
        String ref=(String)item.get("evidence_id");
        assertThat(as(()->knowledge.source(contract,ref)).get("title")).isEqualTo("Current collected prices");
        as(()->{knowledge.validateContext(contract);return null;});
        when(reader.read(eq("market_get_dataset"),any())).thenReturn(Map.of("dataset_code","quote","asset_name","Changed source"));
        assertThatThrownBy(()->as(()->knowledge.source(contract,ref))).isInstanceOf(SecurityException.class).hasMessageContaining("changed");
    }
    @Test void failedRemoteReadSettlesOnlyTheStoppedClientWithoutInventingRemoteStopProof() {
        resourceTask("market_get_history");when(reader.read(anyString(),any())).thenThrow(new IllegalStateException("remote timeout"));
        assertThatThrownBy(()->call("market_get_history",Map.of("dataset","quote"))).isInstanceOf(IllegalStateException.class);
        var entries=jdbc.queryForList("select record_json from runtime_evidence_entry where evidence_type='CODING_RESOURCE_READ_RECEIPT'");
        assertThat(entries).hasSize(1);assertThat(entries.get(0).get("RECORD_JSON").toString()).contains("RUNTIME_HTTP_CLIENT","remoteRequestMayContinue\":true");
    }
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final CodingInvocationContext identity=new CodingInvocationContext();
    final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
    final SkillExecutionScopePort scopes=mock(SkillExecutionScopePort.class);
    final SearchService documents=mock(SearchService.class);
    final PerDocumentIndexService originals=mock(PerDocumentIndexService.class);
    final OpenSearchDocumentIndexService index=mock(OpenSearchDocumentIndexService.class);
    final TextChunker chunker=new TextChunker();
    final SearchProperties properties=new SearchProperties();
    SearchDocument doc;
    CodingKnowledgeService knowledge;
    DurableCodingTaskService tasks;
    JdbcRuntimeEvidenceStore evidence;
    CodingTaskContract contract;
    CodingJournal.Lease lease;
    java.util.concurrent.ScheduledExecutorService timer;
    JdbcTemplate jdbc;
    <T>T as(Supplier<T> action){return identity.as(scope,EnumSet.allOf(CodingTaskAccess.Action.class),action);}
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:knowledge-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        jdbc=new JdbcTemplate(source);evidence=new JdbcRuntimeEvidenceStore(jdbc,json,new DataSourceTransactionManager(source));
        timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        tasks=new DurableCodingTaskService(evidence,json,Clock.systemUTC(),identity,(owner,ref)->true,
            new com.chatchat.api.runtime.coding.workspace.CodingWorkspaceProofVerifier(evidence),Runnable::run,new ConcurrentTaskScheduler(timer));
        evidence.register(new EvidenceRecord(null,"input",scope,"CODING_INPUT_SNAPSHOT","test",evidence.contentHash(Map.of("request","question")),
            Map.of("request","question"),null,0,Map.of()),new EvidenceLineage(null,"input",scope,List.of(),"task","test",null,Map.of()));
        contract=new CodingTaskContract(CodingProtocol.ANALYSIS_VERSION,"task",scope,
            new CodingTaskContract.Objective("Explore backup evidence",List.of("report"),List.of("validation:1")),"runtime-evidence:input",List.of(),"workspace:1",
            new CodingTaskContract.Authorization("decision:1",java.util.stream.Stream.concat(CodingKnowledgeService.TOOLS.stream(),java.util.stream.Stream.of("report_submit")).sorted().toList(),List.of(),List.of(),List.of(),CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of()),
            new CodingTaskContract.Budget("budget:1",Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MILLIS),20,65536,40,0,"1","USD",0,0,10_000_000),
            "env:1","validation:1",new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.JSON_LINES,"schema:1","schema:2",List.of("rows.jsonl"),List.of("application/x-ndjson"),1,128000,List.of()),
            Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","analysisResultMode","TEXT_OR_CODE",
                "knowledgeExploration",Map.of("schemaVersion",CodingKnowledgeService.PROFILE,"agentId","agent")));
        as(()->tasks.submit(contract));lease=as(()->tasks.claim(scope,"task","cline-adapter:test",Duration.ofMinutes(5),tasks.get(scope,"task").revision()));
        doc=SearchDocument.builder().docId("doc").title("Backup design").fileName("backup.md").tenantId("tenant").version(2).latestVersion(true)
            .content("# Recovery design\nBackups run daily. The recovery design promises RTO 30 minutes.\n\n# Test evidence\nNo executed recovery test is recorded.").build();
        when(scopes.resolve(eq("tenant"),eq("owner"),eq("agent"),anyList(),anyList())).thenReturn(new SkillExecutionScopePort.EffectiveScope(List.of("doc"),List.of(),List.of("reader"),true,true));
        when(documents.authorizedKnowledgeIds(any())).thenReturn(List.of("doc"));
        when(originals.openDocumentIndex(eq("doc"),any())).thenAnswer(call->Optional.of(doc));
        when(index.isAvailable()).thenReturn(true);hits();
        knowledge=new CodingKnowledgeService(scopes,documents,originals,index,chunker,properties,tasks,evidence,json);
    }
    void hits(){
        var part=chunker.splitChunks(doc.getContent(),properties.getChunkSize(),properties.getChunkOverlap()).get(0);
        when(index.searchKnowledge(anyString(),anyInt(),any(),anyList())).thenReturn(List.of(new LuceneSearchHit("doc","backup.md",part.section(),"TEXT","doc_0",0,part.content(),0,1,"tenant","owner","private",List.of(),"section",2)));
    }
    @AfterEach void close(){timer.shutdownNow();}
    Map<String,Object> call(String tool,Map<String,Object> args){return as(()->knowledge.call(contract,lease,tool,UUID.randomUUID().toString(),json.valueToTree(args)));}
    Map<String,Object> search(){return call("knowledge_search",Map.of("query","backup recovery","mode","hybrid"));}
    Map<?,?> hit(){return (Map<?,?>)((List<?>)search().get("hits")).get(0);}
    @Test void disablingExecutionDoesNotDisableHistoricalKnowledgeDisclosureChecks() {
        String ref=hit().get("evidence_id").toString();
        var disabled=new KnowledgeDisclosureGuard(jdbc,json,(CodingInvocationContext)null,null,evidence,null);
        assertThatThrownBy(()->disabled.check("tenant","owner",ref)).isInstanceOf(SecurityException.class).hasMessageContaining("unavailable");
        assertThatThrownBy(()->disabled.checkRun("tenant","owner","run")).isInstanceOf(SecurityException.class).hasMessageContaining("unavailable");
        assertThat(disabled.checkRun("tenant","owner","ordinary-run")).isFalse();
        disabled.check("tenant","owner","ordinary answer without document evidence");
        disabled.check("tenant","owner","Skill syntax example: [source](runtime-evidence:knowledge:...)");
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean(JdbcTemplate.class,()->jdbc).withBean(ObjectMapper.class,()->json)
            .withUserConfiguration(KnowledgeDisclosureGuard.class,KnowledgeResponseAdvice.class)
            .withPropertyValues("chatchat.coding.enabled=false").run(app->{
                var guard=app.getBean(KnowledgeDisclosureGuard.class);
                assertThat(app.getBean(KnowledgeResponseAdvice.class)).isNotNull();
                assertThatThrownBy(()->guard.checkRun("tenant","owner","run")).isInstanceOf(SecurityException.class);
                assertThat(guard.checkRun("tenant","owner","ordinary-run")).isFalse();
            });
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void rejectedReportReturnsActionableStructuralFeedbackAndSettlesStoppedTool(boolean schemaFailure) {
        var hit=hit();String ref=hit.get("evidence_id").toString();
        var inputs=mock(com.chatchat.api.runtime.coding.data.CodingInputSnapshotService.class);
        var snapshot=new com.chatchat.api.runtime.coding.data.CodingInputSnapshotService.Snapshot("runtime-evidence:input","file","input.jsonl","a".repeat(64),1,10,"source");
        when(inputs.requireAuthorized(any())).thenReturn(snapshot);when(inputs.describe(any(),any(),any())).thenReturn(Map.of());
        var collector=mock(com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector.class);
        when(collector.collectText(any(),anyString(),any(),anyMap(),anySet(),any())).thenThrow(
            new IllegalArgumentException("Text result does not match the existing row schema"));
        when(collector.textSubmissionFailure(any(),anyString())).thenAnswer(call->{
            String operation=call.getArgument(1),id="report-failure:"+operation;
            var payload=Map.<String,Object>of("taskId","task","operationId",operation,"executorStopped",true,
                "sqlExecuted",false,"executionHandle","runtime-workspace:"+operation);
            evidence.register(new EvidenceRecord(null,id,scope,"CODING_WORKSPACE_TOOL_RECEIPT","test",evidence.contentHash(payload),payload,null,0,Map.of()),
                new EvidenceLineage(null,id,scope,List.of(),"task","test",null,Map.of()));return "runtime-evidence:"+id;
        });
        var plans=mock(com.chatchat.api.runtime.coding.evidence.CodingValidationPlanService.class);
        var controller=new com.chatchat.api.runtime.coding.cline.ClineModelCallbackController(identity,tasks,null,null,inputs,plans,collector,json);
        controller.setKnowledge(knowledge);
        var body=json.createObjectNode();body.set("scope",json.valueToTree(scope));body.put("taskId","task");
        body.put("contractHash",CodingCanonicalHash.sha256(contract));body.put("fencingToken",lease.fencingToken());body.set("contract",json.valueToTree(contract));
        body.set("submission",json.valueToTree(Map.of("toolCallId","report-invalid","report",Map.of(
            "reportMarkdown",schemaFailure?"Recovery is unproven. [design]("+ref+")":"Recovery remains unproven.","evidenceReferences",List.of(ref),"limitations",List.of()))));
        var failure=assertThatThrownBy(()->controller.submitReport(body)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        if(schemaFailure)failure.hasMessageContaining("Report structural validation failed").hasMessageContaining("INPUT_REQUIRED").hasMessageContaining("UNCONFIRMED");
        else failure.hasMessageContaining("lack inline citations").hasMessageContaining(ref);
        assertThat(as(()->tasks.get(scope,"task")).usage().consumed().toolCalls()).isEqualTo(2);
    }
    @Test void searchPrefiltersCurrentAllowedIdsAndReturnsTraceableOriginalEvidence(){
        var result=search();var hit=(Map<?,?>)((List<?>)result.get("hits")).get(0);
        assertThat(result).containsEntry("mode_used","bm25");assertThat(hit.get("version_id").toString()).hasSize(64);
        assertThat(hit.get("text").toString()).contains("No executed recovery test");assertThat(hit.get("page")).isNull();
        verify(index).searchKnowledge(eq("backup recovery"),eq(24),argThat(p->p.userId().equals("owner")&&p.roles().equals(List.of("reader"))),eq(List.of("doc")));
        var ref=hit.get("evidence_id").toString();var record=evidence.find(scope,ref.substring(17)).orElseThrow();
        assertThat(record.payload()).doesNotContainKeys("text","content","title");
        assertThat(as(()->tasks.get(scope,"task")).usage().consumed().toolCalls()).isEqualTo(1);
    }
    @Test void repeatedSearchReadSectionsAndRelatedUseOneTaskAndLiveScope(){
        var hit=hit();var args=Map.<String,Object>of("document_id","doc","version_id",hit.get("version_id"));
        assertThat(call("knowledge_get_document",args)).containsKey("chunk_count");
        assertThat(call("knowledge_list_sections",args)).containsKey("chunks");
        var read=new HashMap<>(args);read.put("chunk_index",0);assertThat(call("knowledge_read",read)).containsKey("chunks");
        assertThat(call("knowledge_related",args)).containsEntry("relationType","LEXICAL_CANDIDATES_NOT_VERIFIED_LINKS");
        search();verify(scopes,atLeast(12)).resolve(eq("tenant"),eq("owner"),eq("agent"),anyList(),anyList());
    }
    @Test void emptyManagedScopeNeverCallsOpenSearchUnrestricted(){
        when(scopes.resolve(any(),any(),any(),anyList(),anyList())).thenReturn(new SkillExecutionScopePort.EffectiveScope(List.of(),List.of(),List.of(),true,true));
        assertThat((List<?>)search().get("hits")).isEmpty();verifyNoInteractions(index);
    }
    @Test void staleIndexExcerptsAreNeverReturned(){doc.setContent("# New revision\nReplacement text.");assertThat((List<?>)search().get("hits")).isEmpty();}
    @Test void forgedIndexResultsOutsideAllowedIdsAreIgnoredBeforeSourceRead(){
        when(index.searchKnowledge(anyString(),anyInt(),any(),anyList())).thenReturn(List.of(new LuceneSearchHit("secret","secret","","","secret_0",0,"SECRET",0,1,"tenant","other","private",List.of())));
        assertThat((List<?>)search().get("hits")).isEmpty();verify(originals,never()).openDocumentIndex(eq("secret"),any());
    }
    @ParameterizedTest @ValueSource(strings={"revoke-document","delete-document","update-same-version","revoke-agent","remove-agent-binding"})
    void invalidatedEvidenceBlocksReadsModelContextHistoricalSourcesAndCitations(String change){
        var hit=hit();String ref=hit.get("evidence_id").toString();
        switch(change){
            case "revoke-document" -> when(originals.openDocumentIndex(eq("doc"),any())).thenReturn(Optional.empty());
            case "delete-document" -> when(originals.openDocumentIndex(eq("doc"),any())).thenReturn(Optional.empty());
            case "update-same-version" -> doc.setContent("Revoked original replaced");
            case "revoke-agent" -> when(scopes.resolve(any(),any(),any(),anyList(),anyList())).thenReturn(SkillExecutionScopePort.EffectiveScope.denied(List.of()));
            default -> when(scopes.resolve(any(),any(),any(),anyList(),anyList())).thenReturn(new SkillExecutionScopePort.EffectiveScope(List.of(),List.of(),List.of(),true,true));
        }
        assertThatThrownBy(()->as(()->{knowledge.validateContext(contract);return null;})).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->call("knowledge_read",Map.of("document_id","doc","version_id",hit.get("version_id"),"chunk_index",0))).isInstanceOf(SecurityException.class);
        var guard=new KnowledgeDisclosureGuard(jdbc,json,identity,tasks,evidence,knowledge);
        assertThatThrownBy(()->guard.resolve("tenant","owner",ref)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->guard.checkRun("tenant","owner","run")).isInstanceOf(SecurityException.class);
    }
    @ParameterizedTest @ValueSource(strings={"userId","roles","allowedDocumentIds","tenantId","dsl","index","agentId"})
    void identityGrantsAndDslCannotBeSuppliedByModel(String field){assertThatThrownBy(()->call("knowledge_search",Map.of("query","backup",field,"admin"))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(index);}
    @Test void arbitraryReadIdsVersionsAndOffsetsAreDenied(){
        var hit=hit();
        assertThatThrownBy(()->call("knowledge_read",Map.of("document_id","secret","version_id",hit.get("version_id"),"chunk_index",0))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->call("knowledge_read",Map.of("document_id","doc","version_id","0".repeat(64),"chunk_index",0))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->call("knowledge_read",Map.of("document_id","doc","version_id",hit.get("version_id"),"chunk_index",-1))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void citationClaimsNeedAnExactDeliveredReferenceAndInlineLocator(){
        var hit=hit();String ref=hit.get("evidence_id").toString();
        as(()->{knowledge.validateReport(contract,Map.of("evidenceReferences",List.of(ref),"reportMarkdown","Recovery tests are unproven [Design]("+ref+")"));return null;});
        assertThatThrownBy(()->as(()->{knowledge.validateReport(contract,Map.of("evidenceReferences",List.of("runtime-context:run"),"reportMarkdown","Proven"));return null;})).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->as(()->{knowledge.validateReport(contract,Map.of("evidenceReferences",List.of(ref),"reportMarkdown","Proven"));return null;})).isInstanceOf(SecurityException.class);
        var guard=new KnowledgeDisclosureGuard(jdbc,json,identity,tasks,evidence,knowledge);
        assertThat(guard.resolve("tenant","owner",ref)).containsEntry("version",2).containsKey("text");
        assertThatThrownBy(()->guard.resolve("tenant","other",ref)).isInstanceOf(SecurityException.class);
    }
}
