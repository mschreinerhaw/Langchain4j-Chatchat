package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.protocol.ModelProtocolJson;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HarnessCodingIntegrationTest {
    @Test void modelDelegatesOnlyAdvertisedCodingAndThenSeesItsCommittedDataset() {
        var model=mock(ChatModel.class);var calls=new AtomicInteger();var dispatched=new AtomicInteger();
        var meta=new LinkedHashMap<String,Object>();
        var scope=GovernanceIsolationScope.runtime("tenant","user","run","request","conversation");
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation->{
            ChatRequest request=invocation.getArgument(0);
            String prompt=request.messages().toString();
            int turn=calls.incrementAndGet();
            Map<String,Object> reply;
            if(turn==1){
                assertThat(prompt).contains("RUN_CODING");
                reply=Map.of("schemaVersion","model_native_analysis.v2","decision",Map.of("action","CONTINUE"),
                    "output_type","intermediate","plan",plan(),"evidenceRequests",List.of(Map.of("operation","RUN_CODING","requestId","calculation-1",
                        "datasetReference","source","instruction","Compute the requested statistic","outputSchema",Map.of("type","object"))));
            }else{
                assertThat(prompt).contains("coding-result","STRUCTURAL_ONLY","17");
                reply=Map.of("schemaVersion","model_native_analysis.v2","decision",Map.of("action","COMPLETE"),
                    "output_type","final","content","The computed statistic is 17; business correctness remains unevaluated.");
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(ModelProtocolJson.compact(reply))).build();
        });
        AnalysisCodingPort port=new AnalysisCodingPort(){
            public Map<String,String> capabilities(){return Map.of("RUN_CODING","Runtime calculation");}
            public Map<String,Object> execute(AnalysisCoverageCoordinator.Request context,Map<String,Object> request,Map<String,Dataset> sources){
                assertThat(context.isolationScope()).isEqualTo(scope);assertThat(sources).containsKey("source");
                dispatched.incrementAndGet();
                sources.put("coding-result",new Dataset("coding-result",Map.of("validationScope","STRUCTURAL_ONLY"),List.of(Map.of("statistic",17))));
                return Map.of("taskId","coding-task","status","COMPLETED","resultAvailable",true,
                    "datasetReference","coding-result","validationScope","STRUCTURAL_ONLY","businessValidation","NOT_EVALUATED");
            }
        };
        var context=new AnalysisCoverageCoordinator.Request(model,"Compute a statistic",null,Map.of(),meta,()->false,()->{},scope,null);
        var result=new ModelNativeAnalysisHarness(3).withCoding(port,context).execute(context.query(),
            List.of(new Dataset("source",Map.of(),List.of(Map.of("value",5)))),model,scope,AnalysisEvidenceSpillStore.disabled(),meta,()->{},trace->{});
        assertThat(dispatched).hasValue(1);assertThat(calls).hasValue(2);
        assertThat(result.datasetReferences()).contains("source","coding-result");
        assertThat(result.markdown()).contains("17");
        assertThat(meta).containsEntry("publicationState","REQUESTED");
    }
    static Map<String,Object> plan(){return Map.of("version","1.0","intent",Map.of("type","analysis","goal","Compute a statistic"),
        "context",Map.of("key_facts",List.of(),"assumptions",List.of(),"missing_info",List.of(),"constraints",List.of()),
        "plan",Map.of("steps",List.of(Map.of("id",1,"action_type","reasoning","input",Map.of(),"depends_on",List.of()))));}

    @Test void modelMayFinishDirectlyWithoutInvokingAvailableCoding() {
        var model=mock(ChatModel.class);var port=mock(AnalysisCodingPort.class);
        when(port.capabilities()).thenReturn(Map.of("RUN_CODING","Optional delegated calculation"));
        when(model.chat(any(ChatRequest.class))).thenAnswer(call->{
            assertThat(((ChatRequest)call.getArgument(0)).messages().toString()).contains("Coding is optional", "RUN_CODING");
            return reply("COMPLETE", "The supplied record already answers the question.", List.of());
        });
        var result=run(model,port,new LinkedHashMap<>());
        assertThat(result.modelCalls()).isEqualTo(1);
        assertThat(result.datasetReferences()).containsExactly("source");
        verify(port,never()).execute(any(),anyMap(),anyMap());
    }

    @Test void codingUsesProfileFirstAndAcceptsMultipleDeliveredHandles() {
        var model=mock(ChatModel.class);var port=resultPort();var turns=new AtomicInteger();
        var scope=GovernanceIsolationScope.runtime("tenant","user","run","request","conversation");
        var metadata=new LinkedHashMap<String,Object>();
        var context=new AnalysisCoverageCoordinator.Request(model,"Join delivered sources",null,Map.of(),metadata,
            ()->false,()->{},scope,null);
        when(model.chat(any(ChatRequest.class))).thenAnswer(call->{
            var prompt=((ChatRequest)call.getArgument(0)).messages().toString();
            if(turns.incrementAndGet()==1){
                assertThat(prompt).contains("CODING_PROFILE_ONLY","RUN_CODING","Python processes");
                assertThat(prompt).doesNotContain("FIRST_ROW", "selectedRecords");
                assertThat(prompt).doesNotContain("HIDDEN_SECOND_ROW");
                var request=new LinkedHashMap<>(codingRequest("left"));
                request.remove("datasetReference");request.put("datasetReferences",List.of("left","right"));
                return reply("CONTINUE","",List.of(request));
            }
            assertThat(prompt).contains("coding-result");
            return reply("COMPLETE","Joined result from Python evidence.",List.of());
        });
        var result=new ModelNativeAnalysisHarness(3).withCoding(port,context).execute(context.query(),
            List.of(new Dataset("left",Map.of(),List.of(Map.of("value","FIRST_ROW"),Map.of("value","HIDDEN_SECOND_ROW"))),
                new Dataset("right",Map.of(),List.of(Map.of("key","first"),Map.of("key","second")))),
            model,scope,AnalysisEvidenceSpillStore.disabled(),metadata,()->{},trace->{});
        assertThat(result.datasetReferences()).contains("left","right","coding-result");
        var submitted=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(port).execute(eq(context),submitted.capture(),anyMap());
        assertThat(submitted.getValue()).containsEntry("datasetReferences",List.of("left","right"));
    }

    @Test void disabledCodingIsNotAdvertisedAndModelCanContinueAfterAnUnsupportedRequest() {
        var model=mock(ChatModel.class);var turn=new AtomicInteger();
        when(model.chat(any(ChatRequest.class))).thenAnswer(call->{
            String prompt=((ChatRequest)call.getArgument(0)).messages().toString();
            assertThat(prompt).contains("\"codingOperations\":{}");
            if(turn.incrementAndGet()==1)return reply("CONTINUE","",List.of(codingRequest("source")));
            assertThat(prompt).contains("REQUEST_REJECTED", "Operation is not registered");
            return reply("COMPLETE","Coding is unavailable; the original data remains readable.",List.of());
        });
        assertThat(run(model,null,new LinkedHashMap<>()).datasetReferences()).containsExactly("source");
        assertThat(turn).hasValue(2);
    }

    @Test void modelCanCorrectAnUndeliveredReferenceBeforeAnyCodingDispatch() {
        var model=mock(ChatModel.class);var port=resultPort();var turn=new AtomicInteger();
        when(model.chat(any(ChatRequest.class))).thenAnswer(call->{
            String prompt=((ChatRequest)call.getArgument(0)).messages().toString();
            return switch(turn.incrementAndGet()){
                case 1 -> reply("CONTINUE","",List.of(codingRequest("another-run")));
                case 2 -> {
                    assertThat(prompt).contains("CODING_INPUT_NOT_DELIVERED", "UNCHANGED");
                    verify(port,never()).execute(any(),anyMap(),anyMap());
                    yield reply("CONTINUE","",List.of(codingRequest("source")));
                }
                default -> {
                    assertThat(prompt).contains("coding-result", "NOT_EVALUATED");
                    yield reply("COMPLETE","The corrected calculation returned a scoped result.",List.of());
                }
            };
        });
        assertThat(run(model,port,new LinkedHashMap<>()).datasetReferences()).contains("source","coding-result");
        verify(port).execute(any(),anyMap(),anyMap());
    }

    @Test void codingTaskStateSurvivesReplacementOfTheImmediateEvidenceReceipts() {
        var model=mock(ChatModel.class);var port=resultPort();var turn=new AtomicInteger();var metadata=new LinkedHashMap<String,Object>();
        when(model.chat(any(ChatRequest.class))).thenAnswer(call->{
            var request=(ChatRequest)call.getArgument(0);
            return switch(turn.incrementAndGet()){
                case 1 -> reply("CONTINUE","",List.of(codingRequest("source")));
                case 2 -> reply("CONTINUE","",List.of(Map.of("operation","READ_RECORDS","datasetReference","source","fromRecord",1,"limit",1)));
                default -> {
                    var layers=new com.fasterxml.jackson.databind.ObjectMapper().readTree(((UserMessage)request.messages().get(1)).singleText());
                    assertThat(layers.path("L1").path("codingTasks").size()).isEqualTo(1);
                    assertThat(layers.path("L1").path("codingTasks").get(0).path("taskId").asText()).isEqualTo("coding-task");
                    assertThat(layers.path("L1").path("codingTasks").get(0).path("resultAvailable").asBoolean()).isTrue();
                    assertThat(layers.path("L3").path("receipts").toString()).doesNotContain("coding-task");
                    yield reply("COMPLETE","The computation remains traceable after other reads.",List.of());
                }
            };
        });
        run(model,port,metadata);
        assertThat((List<?>)metadata.get("harnessCodingTasks")).hasSize(1);
    }

    @Test void mismatchedCodingContextIsRejectedBeforeReadingOrCallingTheModel() {
        var model=mock(ChatModel.class);var port=mock(AnalysisCodingPort.class);var metadata=new LinkedHashMap<String,Object>();
        var scope=GovernanceIsolationScope.runtime("tenant","user","run","request","conversation");
        var other=GovernanceIsolationScope.runtime("other-tenant","user","run","request","conversation");
        var contexts=Arrays.asList(null,
            new AnalysisCoverageCoordinator.Request(model,"Question",null,Map.of(),metadata,()->false,()->{},other,null),
            new AnalysisCoverageCoordinator.Request(mock(ChatModel.class),"Question",null,Map.of(),metadata,()->false,()->{},scope,null),
            new AnalysisCoverageCoordinator.Request(model,"Question",null,Map.of(),new LinkedHashMap<>(),()->false,()->{},scope,null));
        for(var context:contexts)
            assertThatThrownBy(()->new ModelNativeAnalysisHarness(3).withCoding(port,context).execute("Question",List.of(),model,scope,
                AnalysisEvidenceSpillStore.disabled(),metadata,()->{},trace->{})).isInstanceOf(SecurityException.class);
        verifyNoInteractions(model,port);
    }

    @Test void realAuthorizationFailuresAreNotConvertedToModelCorrectableInputErrors() {
        var model=mock(ChatModel.class);var port=resultPort();
        when(model.chat(any(ChatRequest.class))).thenReturn(reply("CONTINUE","",List.of(codingRequest("source"))));
        doThrow(new SecurityException("Authorization revoked")).when(port).execute(any(),anyMap(),anyMap());
        assertThatThrownBy(()->run(model,port,new LinkedHashMap<>())).isInstanceOf(SecurityException.class).hasMessage("Authorization revoked");
        verify(port).execute(any(),anyMap(),anyMap());
    }

    @Test void incompleteCodingReceiptsDoNotInventAResultOrForceModelCompletion() {
        for(String state:List.of("FAILED","RUNNING","CAPACITY_UNAVAILABLE")){
            var model=mock(ChatModel.class);var port=resultPort();var turn=new AtomicInteger();
            doReturn(Map.of("taskId","coding-task","status",state,
                "resultAvailable",false,"businessValidation","NOT_EVALUATED")).when(port).execute(any(),anyMap(),anyMap());
            when(model.chat(any(ChatRequest.class))).thenAnswer(call->{
                if(turn.incrementAndGet()==1)return reply("CONTINUE","",List.of(codingRequest("source")));
                assertThat(((ChatRequest)call.getArgument(0)).messages().toString()).contains(state,"\"resultAvailable\":false");
                return reply("PARTIAL_COMPLETE","Only the original data is available; Coding status is "+state,List.of());
            });
            var result=run(model,port,new LinkedHashMap<>());
            assertThat(result.datasetReferences()).containsExactly("source");
            assertThat(result.markdown()).contains(state);
        }
    }

    @Test void aModelPlanMustBeAcceptedBeforeTheCodingRequestCanExecute() {
        var model=mock(ChatModel.class);var port=resultPort();var turn=new AtomicInteger();
        when(model.chat(any(ChatRequest.class))).thenAnswer(call->{
            int current=turn.incrementAndGet();
            if(current==1)return ChatResponse.builder().aiMessage(AiMessage.from(ModelProtocolJson.compact(Map.of(
                "schemaVersion","model_native_analysis.v2","decision",Map.of("action","CONTINUE"),"output_type","intermediate",
                "evidenceRequests",List.of(codingRequest("source")))))).build();
            if(current==2){
                assertThat(((ChatRequest)call.getArgument(0)).messages().toString()).contains("PLAN_REQUIRED");
                verify(port,never()).execute(any(),anyMap(),anyMap());
                return reply("CONTINUE","",List.of(codingRequest("source")));
            }
            return reply("COMPLETE","The calculation followed the accepted model-authored plan.",List.of());
        });
        run(model,port,new LinkedHashMap<>());
        verify(port).execute(any(),anyMap(),anyMap());
    }

    @Test void thePromptKeepsOnlyEightRecentTaskSummariesWithoutLosingTheRuntimeCatalog() {
        var model=mock(ChatModel.class);var port=resultPort();var turn=new AtomicInteger();var next=new AtomicInteger();
        doAnswer(call->Map.of("taskId","task-"+next.incrementAndGet(),"status","FAILED","resultAvailable",false))
            .when(port).execute(any(),anyMap(),anyMap());
        when(model.chat(any(ChatRequest.class))).thenAnswer(call->{
            if(turn.incrementAndGet()<=3){
                var requests=new ArrayList<Map<String,Object>>();
                for(int index=0;index<4;index++){
                    var item=new LinkedHashMap<>(codingRequest("source"));item.put("requestId","calculation-"+turn.get()+"-"+index);requests.add(item);
                }
                return reply("CONTINUE","",requests);
            }
            var layers=new com.fasterxml.jackson.databind.ObjectMapper().readTree(((UserMessage)((ChatRequest)call.getArgument(0)).messages().get(1)).singleText());
            var tasks=layers.path("L1").path("codingTasks");
            assertThat(tasks.size()).isEqualTo(8);
            assertThat(tasks.get(0).path("taskId").asText()).isEqualTo("task-5");
            assertThat(tasks.get(7).path("taskId").asText()).isEqualTo("task-12");
            return reply("PARTIAL_COMPLETE","The delegated tasks failed; no derived result is available.",List.of());
        });
        var metadata=new LinkedHashMap<String,Object>();run(model,port,metadata);
        assertThat((List<?>)metadata.get("harnessCodingTasks")).hasSize(12);
    }

    @Test void pythonPolicyRejectsDirectCompletionAndRawReadsBeforeAdmittingComputedResults() {
        var model=mock(ChatModel.class);var port=resultPort();var turn=new AtomicInteger();
        doCallRealMethod().when(port).executeObserved(any(),anyMap(),anyMap(),any());
        var metadata=new LinkedHashMap<String,Object>();metadata.put("analysisExecutionPolicy","PYTHON_REQUIRED");
        when(model.chat(any(ChatRequest.class))).thenAnswer(call->{
            var input=(ChatRequest)call.getArgument(0);
            var layers=new com.fasterxml.jackson.databind.ObjectMapper().readTree(((UserMessage)input.messages().get(1)).singleText());
            assertThat(layers.path("L3").path("datasets").get(0).toString()).doesNotContain("selectedRecords", "\"records\"");
            return switch(turn.incrementAndGet()) {
                case 1 -> reply("COMPLETE","Uncomputed answer",List.of());
                case 2 -> {
                    assertThat(layers.toString()).contains("PYTHON_RESULT_REQUIRED");
                    yield reply("CONTINUE","",List.of(Map.of("operation","READ_RECORDS","datasetReference","source","fromRecord",1,"limit",100),
                        Map.of("operation","BATCH_MODEL_INFERENCE","datasetReference","source"),
                        Map.of("operation","QUERY_DATASET","datasetReference","source")));
                }
                case 3 -> {
                    assertThat(layers.toString()).contains("RAW_DATA_EXECUTION_ONLY","PYTHON_EXECUTION_REQUIRED");
                    verify(port,never()).execute(any(),anyMap(),anyMap());
                    yield reply("CONTINUE","",List.of(codingRequest("source")));
                }
                default -> {
                    assertThat(layers.toString()).contains("coding-result", "17");
                    yield reply("COMPLETE","Computed result: 17",List.of());
                }
            };
        });
        assertThat(run(model,port,metadata).markdown()).contains("17");
        verify(port).execute(any(),anyMap(),anyMap());
        var catalog=(List<?>)metadata.get("runtimeReturnedReportDatasets");
        assertThat(catalog).hasSize(1);
    }

    @Test void pythonPolicyDoesNotFallBackToModelWhenWorkerIsDisabled() {
        var model=mock(ChatModel.class);var metadata=new LinkedHashMap<String,Object>();
        metadata.put("analysisExecutionPolicy","PYTHON_REQUIRED");
        assertThatThrownBy(()->run(model,null,metadata)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PYTHON_ANALYSIS_UNAVAILABLE");
        verifyNoInteractions(model);
    }

    @Test void emptyAnalysisCannotClaimCompleteWithoutComputedEvidence() {
        var model=mock(ChatModel.class);var port=mock(AnalysisCodingPort.class);var turn=new AtomicInteger();
        when(port.capabilities()).thenReturn(Map.of("RUN_CODING","Python calculation"));
        when(model.chat(any(ChatRequest.class))).thenAnswer(call->{
            if(turn.incrementAndGet()==1)return reply("COMPLETE","Uncomputed answer",List.of());
            assertThat(((ChatRequest)call.getArgument(0)).messages().toString()).contains("PYTHON_RESULT_REQUIRED");
            return reply("PARTIAL_COMPLETE","No authorized data is available for calculation.",List.of());
        });
        var metadata=new LinkedHashMap<String,Object>();metadata.put("analysisExecutionPolicy","PYTHON_REQUIRED");
        var scope=GovernanceIsolationScope.runtime("tenant","user","run","request","conversation");
        var context=new AnalysisCoverageCoordinator.Request(model,"Question",null,Map.of(),metadata,()->false,()->{},scope,null);
        var result=new ModelNativeAnalysisHarness(3).withCoding(port,context).execute("Question",List.of(),model,scope,
            AnalysisEvidenceSpillStore.disabled(),metadata,()->{},event->{});
        assertThat(result.markdown()).contains("No authorized data");assertThat(turn).hasValue(2);
        verify(port,never()).execute(any(),anyMap(),anyMap());
    }

    private static ModelNativeAnalysisHarness.Result run(ChatModel model,AnalysisCodingPort port,Map<String,Object> metadata){
        var scope=GovernanceIsolationScope.runtime("tenant","user","run","request","conversation");
        var context=new AnalysisCoverageCoordinator.Request(model,"Question",null,Map.of(),metadata,()->false,()->{},scope,null);
        return new ModelNativeAnalysisHarness(5).withCoding(port,context).execute("Question",
            List.of(new Dataset("source",Map.of(),List.of(Map.of("value",5)))),model,scope,
            AnalysisEvidenceSpillStore.disabled(),metadata,()->{},trace->{});
    }
    private static Map<String,Object> codingRequest(String reference){
        return Map.of("operation","RUN_CODING","requestId","calculation-1","datasetReference",reference,
            "instruction","Compute the statistic chosen by the model","outputSchema",Map.of("type","object"));
    }
    private static ChatResponse reply(String action,String content,List<Map<String,Object>> requests){
        return ChatResponse.builder().aiMessage(AiMessage.from(ModelProtocolJson.compact(Map.of(
            "schemaVersion","model_native_analysis.v2","decision",Map.of("action",action),
            "output_type",action.equals("CONTINUE")?"intermediate":"final","content",content,"plan",plan(),"evidenceRequests",requests)))).build();
    }
    private static AnalysisCodingPort resultPort(){
        var port=mock(AnalysisCodingPort.class);
        when(port.capabilities()).thenReturn(Map.of("RUN_CODING","Optional calculation"));
        when(port.execute(any(),anyMap(),anyMap())).thenAnswer(call->{
            Map<String,Dataset> sources=call.getArgument(2);
            sources.put("coding-result",new Dataset("coding-result",Map.of("businessValidation","NOT_EVALUATED"),List.of(Map.of("value",17))));
            return Map.of("taskId","coding-task","status","COMPLETED","resultAvailable",true,
                "datasetReference","coding-result","businessValidation","NOT_EVALUATED");
        });
        return port;
    }
}
