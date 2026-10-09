package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.*;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataWorkspaceOperationsTest {
    private final GovernanceIsolationScope scope=GovernanceIsolationScope.runtime("tenant","user","run","request","conversation");
    private final Map<String,Dataset> sources=new LinkedHashMap<>();
    private final Store store=new Store();
    private final ChatModel model=mock(ChatModel.class);
    private final Map<String,Object> metadata=new LinkedHashMap<>();
    private DataWorkspaceOperations operations(){return new DataWorkspaceOperations(sources,store,scope,model,()->{},event->{},metadata);}
    private void records(int count){sources.put("source",new Dataset("source",Map.of(),IntStream.rangeClosed(1,count).mapToObj(i -> Map.<String,Object>of("amount",i,"text","Record "+i,"category",i%2==0?"even":"odd")).toList()));}
    private Map<String,Object> query(){return Map.of("operation","QUERY_DATASET","datasetReference","source","groupBy",List.of(),"aggregates",List.of(Map.of("function","COUNT","as","count"),Map.of("function","SUM","field","amount","as","sum"),Map.of("function","AVG","field","amount","as","average")));}
    private Map<String,Object> batch(){return Map.of("operation","BATCH_MODEL_INFERENCE","datasetReference","source","scope",Map.of("mode","all"),"batchSize",100,"concurrency",3,"instruction","Extract the supplied record's explicit text into a typed result.","fields",List.of("text"),"outputSchema",Map.of("type","object","properties",Map.of("label",Map.of("type","string")),"required",List.of("label"),"additionalProperties",false));}
    @Test void computesAllTenThousandRecordsAndKeepsResultHandleOutsideModelContext(){
        records(10_000);var receipt=operations().execute(query());
        assertThat(receipt).containsEntry("scannedRecords",10_000L).containsEntry("matchedRecords",10_000L).containsEntry("truncated",false);
        var result=sources.get(receipt.get("datasetReference")).handle().readPage(0,1).rows().get(0);
        assertThat(((Number)result.get("count")).longValue()).isEqualTo(10_000);
        assertThat(new BigDecimal(result.get("sum").toString())).isEqualByComparingTo("50005000");
        assertThat(new BigDecimal(result.get("average").toString())).isEqualByComparingTo("5000.5");
        verifyNoInteractions(model);
        var incompatible=new LinkedHashMap<>(query());incompatible.put("filters",List.of(Map.of("field","amount","operator","GT","value","2")));
        assertThatThrownBy(()->operations().execute(incompatible)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compatible");
        assertThat(sources.get("source").recordCount()).isEqualTo(10_000);
    }
    @Test void onlyModelSelectedPredicatesExcludeRows(){
        records(10_000);var request=new LinkedHashMap<>(query());request.put("groupBy",List.of("category"));request.put("filters",List.of(Map.of("field","amount","operator","GT","value",9990)));
        var receipt=operations().execute(request);
        assertThat(receipt).containsEntry("scannedRecords",10_000L).containsEntry("matchedRecords",10L);
        assertThat(sources.get(receipt.get("datasetReference")).recordCount()).isEqualTo(2);
    }
    @Test void tenThousandSemanticRecordsRetainThirtySevenFailuresAndRetryOnlyThoseRecords()throws Exception{
        records(10_000);var calls=new AtomicInteger();var retry=new AtomicBoolean();
        var seen=ConcurrentHashMap.<Long>newKeySet();
        when(model.chat(any(String.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();String prompt=invocation.getArgument(0);
            var input=new ObjectMapper().readValue(prompt.substring(prompt.indexOf('\n')+1),new TypeReference<Map<String,Object>>(){});
            List<?> rows=(List<?>)input.get("records");assertThat(rows.size()).isLessThanOrEqualTo(100);
            var outputs=new ArrayList<Map<String,Object>>();
            for(Object raw:rows){var row=(Map<?,?>)raw;long id=((Number)row.get("record")).longValue();seen.add(id);if(id>37||retry.get())outputs.add(Map.of("record",id,"output",Map.of("label","Explicit text")));}
            return ModelProtocolJson.compact(Map.of("records",outputs));
        });
        var receipt=operations().execute(batch());
        assertThat(seen).hasSize(10_000);assertThat(calls).hasValue(100);
        assertThat(receipt).containsEntry("totalRecords",10_000L).containsEntry("processedRecords",9963L).containsEntry("failedRecords",37L).containsEntry("semanticQualityCertified",false).containsEntry("truncated",false);
        var handle=sources.get(receipt.get("datasetReference")).handle();assertThat(handle.recordCount()).isEqualTo(10_000);
        assertThat(handle.readPage(0,37).rows()).allSatisfy(row -> assertThat(row).containsEntry("status","FAILED").containsKey("reason").containsKey("sourceRef"));
        assertThat(handle.readPage(9999,1).rows().get(0)).containsEntry("status","COMPLETED").containsEntry("sourceRef","source.records[10000]");
        var analysis = operations().execute(Map.of("operation","QUERY_DATASET","datasetReference",receipt.get("datasetReference"),
            "groupBy",List.of("output.label"),"aggregates",List.of(Map.of("function","COUNT","as","count")),
            "filters",List.of(Map.of("field","status","operator","EQ","value","COMPLETED"))));
        assertThat(analysis).containsEntry("scannedRecords",10_000L).containsEntry("matchedRecords",9963L);
        assertThat(sources.get(analysis.get("datasetReference")).handle().readPage(0,1).rows().get(0).get("output.label")).isEqualTo("Explicit text");
        seen.clear();retry.set(true);var request=new LinkedHashMap<>(batch());request.put("retryFailed",true);
        var retried=operations().execute(request);assertThat(seen).hasSize(37);assertThat(calls).hasValue(101);
        assertThat(retried).containsEntry("datasetReference",receipt.get("datasetReference")).containsEntry("processedRecords",10_000L).containsEntry("failedRecords",0L);
        var resumed=operations().execute(batch());assertThat(calls).hasValue(101);assertThat(resumed).containsEntry("processedRecords",10_000L);
    }
    @Test void schemaScopeAndFieldValidationRejectUnsupportedRequestsBeforeInference(){
        records(5);var request=new LinkedHashMap<>(batch());request.put("outputSchema",Map.of("type","object","oneOf",List.of()));
        assertThatThrownBy(()->operations().execute(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported");
        request.put("outputSchema",batch().get("outputSchema"));request.put("scope",Map.of("mode","sample"));
        assertThatThrownBy(()->operations().execute(request)).isInstanceOf(IllegalArgumentException.class);
        var unknown=new LinkedHashMap<>(query());unknown.put("datasetReference","another-run");
        assertThatThrownBy(()->operations().execute(unknown)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(model);
    }
    @Test void invalidPerRecordSchemaFailsExecutionWithoutDroppingTheRecord(){
        records(2);when(model.chat(any(String.class))).thenReturn("{\"records\":[{\"record\":1,\"output\":{\"label\":123}},{\"record\":2,\"output\":{\"label\":\"valid\"}}]}");
        var receipt=operations().execute(batch());assertThat(receipt).containsEntry("processedRecords",1L).containsEntry("failedRecords",1L);
        assertThat(sources.get(receipt.get("datasetReference")).recordCount()).isEqualTo(2);
    }
    @Test void catalogHasContinuationAndOriginalHandlesRemainDiscoverable(){
        for(int i=0;i<25;i++)sources.put("source"+i,new Dataset("source"+i,Map.of(),List.of(Map.of("value",i))));
        var receipt=operations().execute(Map.of("operation","CATALOG","offset",20,"limit",10));
        assertThat(receipt).containsEntry("totalDatasets",25).containsEntry("nextOffset",25).containsEntry("hasMore",false);
        assertThat((List<?>)receipt.get("datasets")).hasSize(5);
    }
    @Test void cancellationDoesNotBecomeSuccessfulSemanticCoverage(){
        records(10_000);var operations=new DataWorkspaceOperations(sources,store,scope,model,()->{throw new java.util.concurrent.CancellationException();},event->{},metadata);
        assertThatThrownBy(()->operations.execute(batch())).isInstanceOf(java.util.concurrent.CancellationException.class);verifyNoInteractions(model);
    }
    @Test void fullScopeCheckpointDoesNotCrossTenantOrRunAndDurabilityIsRequired(){
        records(2);
        when(model.chat(any(String.class))).thenReturn("{\"records\":[{\"record\":1,\"output\":{\"label\":\"one\"}},{\"record\":2,\"output\":{\"label\":\"two\"}}]}");
        operations().execute(batch());
        var other = GovernanceIsolationScope.runtime("other-tenant","user","other-run","request","conversation");
        var isolated = new DataWorkspaceOperations(new LinkedHashMap<>(sources),store,other,model,()->{},event->{},new LinkedHashMap<>());
        isolated.execute(batch());verify(model,times(2)).chat(any(String.class));
        var disabled = new DataWorkspaceOperations(sources,AnalysisEvidenceSpillStore.disabled(),scope,model,()->{},event->{},metadata);
        assertThat(disabled.capabilities()).doesNotContainKey("BATCH_MODEL_INFERENCE");
        assertThat(operations().capabilities()).containsKey("BATCH_MODEL_INFERENCE");
        assertThatThrownBy(()->disabled.execute(batch())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("durable");
    }
    @Test void mainBrainChoosesFullComputationAndReceivesDerivedVisualizationCatalog(){
        records(10_000);var turns=new AtomicInteger();when(model.chat(any(String.class))).thenAnswer(invocation -> {
            String prompt=invocation.getArgument(0);
            if(turns.incrementAndGet()==1){assertThat(prompt.length()).isLessThan(100_000);return ModelProtocolJson.compact(Map.of("schemaVersion","model_native_analysis.v1","completed",false,"evidenceRequests",List.of(query())));}
            assertThat(prompt).contains("50005000","workspace:","5000.5");return "The computed total is 50,005,000 across 10,000 records.";
        });
        var result=new ModelNativeAnalysisHarness(8).execute("Compute the total",List.copyOf(sources.values()),model,scope,store,metadata,()->{},span->{});
        assertThat(result.modelCalls()).isEqualTo(2);assertThat(result.datasetReferences()).anyMatch(ref->ref.startsWith("workspace:"));
        assertThat(result.markdown()).contains("50,005,000");assertThat(metadata).doesNotContainKeys("analysisDriverPipelineContext","analysisAcceptedWorkerCount");
    }
    static final class Store implements AnalysisEvidenceSpillStore {
        private final Map<String,String> checkpoints=new ConcurrentHashMap<>();private final Map<String,byte[]> pages=new ConcurrentHashMap<>();
        public boolean isEnabled(){return true;}
        public SpillReference spill(GovernanceIsolationScope scope,String id,String hash,byte[] payload){String key=scope.partitionKey()+":"+id+":"+hash;pages.put(key,payload);return new SpillReference("","",key,id,hash,payload.length,0);}
        public byte[] read(GovernanceIsolationScope scope,SpillReference ref){if(!ref.storageKey().startsWith(scope.partitionKey()+":"))throw new IllegalArgumentException("scope");return pages.get(ref.storageKey());}
        public Optional<String> readCheckpoint(GovernanceIsolationScope scope,String key,String hash){return Optional.ofNullable(checkpoints.get(scope.partitionKey()+":"+key+":"+hash));}
        public void checkpoint(GovernanceIsolationScope scope,String key,String hash,String value){checkpoints.put(scope.partitionKey()+":"+key+":"+hash,value);}
    }
}
