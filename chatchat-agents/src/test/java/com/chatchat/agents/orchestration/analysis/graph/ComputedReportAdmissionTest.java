package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ComputedReportAdmissionTest {
    private final GovernanceIsolationScope scope=GovernanceIsolationScope.runtime("tenant","user","run","request","conversation");
    @Test void runtimeCardinalityAndFailureFactsDoNotExposeRawRowsOrArbitraryReceiptFields() {
        var source=mock(com.chatchat.agents.orchestration.analysis.dataset.DatasetHandle.class);
        when(source.recordCount()).thenReturn(0L);when(source.recordCountExact()).thenReturn(true);
        when(source.contentSha256()).thenReturn("source-hash");
        var model=mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"status\":\"SUPPORTED\",\"reason\":\"Provenance and computed text support the report\",\"unsupportedClaims\":[]}");
        var receipt=Map.<String,Object>of("operation","RUN_CODING","requestId","failed-request","taskId","failed-task",
            "status","EXECUTION_FAILED","resultAvailable",false,"rawData","RAW_RECEIPT_MUST_NOT_LEAK",
            "instruction","PRIVATE_INSTRUCTION_MUST_NOT_LEAK","validationStatus","INCONCLUSIVE","businessValidation","NOT_EVALUATED");
        var sourceViews=List.<Map<String,Object>>of(Map.of("datasetReference","source","profile",Map.of("fields",List.of(
            Map.of("field","text","min","PROFILE_VALUE_MUST_NOT_LEAK")))));
        var result=new ComputedReportAdmission().review("Analyze","The delivered source is empty; the earlier coding request failed.",
            Map.of("source",new Dataset("source",Map.of("secret","PRIVATE_CONTEXT_MUST_NOT_LEAK"),source),
                "result",new Dataset("result",Map.of("coding",Map.of("validationStatus","INCONCLUSIVE",
                    "businessValidation","NOT_EVALUATED","summary","WORKER_PROSE_MUST_NOT_LEAK")),
                    List.of(Map.of("finding","No records delivered")))),
            Set.of("result"),model,scope,AnalysisEvidenceSpillStore.disabled(),()->{},
            List.of(Map.of("reads",List.of(Map.of("receipt",receipt)))),sourceViews);
        assertThat(result.value()).containsEntry("status","SUPPORTED");
        verify(source,never()).readPage(anyLong(),anyInt());
        verify(model).chat(argThat((String prompt)->prompt.contains("\"recordCountExact\":true")
            && prompt.contains("\"recordCount\":0") && prompt.contains("EXECUTION_FAILED")
            && prompt.contains("failed-request") && prompt.contains("\"fieldNames\":[\"text\"]")
            && prompt.contains("\"validationProvenance\":{\"businessValidation\":\"NOT_EVALUATED\",\"validationStatus\":\"INCONCLUSIVE\"}")
            && !prompt.contains("MUST_NOT_LEAK")));
    }
    @Test void inexactCardinalityRemainsALowerBound() {
        var source=mock(com.chatchat.agents.orchestration.analysis.dataset.DatasetHandle.class);
        when(source.recordCount()).thenReturn(0L);when(source.recordCountExact()).thenReturn(false);
        when(source.contentSha256()).thenReturn("source-hash");
        var model=mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"status\":\"INCONCLUSIVE\",\"reason\":\"Lower bound does not prove emptiness\",\"unsupportedClaims\":[]}");
        assertThat(new ComputedReportAdmission().review("Analyze","No source records exist.",
            Map.of("source",new Dataset("source",Map.of(),source)),Set.of(),model,scope,AnalysisEvidenceSpillStore.disabled(),()->{}).value())
            .containsEntry("status","INCONCLUSIVE");
        verify(model).chat(argThat((String prompt)->prompt.contains("\"recordCountExact\":false")));
        verify(source,never()).readPage(anyLong(),anyInt());
    }
    @Test void finalReviewUsesOnlyAdmittedComputedResultsAndAllowsTextAnalysis() {
        var model=mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"status\":\"SUPPORTED\",\"reason\":\"Supported text finding\",\"unsupportedClaims\":[]}");
        var sources=new LinkedHashMap<String,Dataset>();
        sources.put("raw",new Dataset("raw",Map.of(),List.of(Map.of("secret","RAW_SOURCE_MUST_NOT_REACH_REVIEW"))));
        sources.put("result",new Dataset("result",Map.of(),List.of(Map.of("finding","The document discusses maintenance."))));
        var result=new ComputedReportAdmission().review("Summarize themes","The document discusses maintenance.",sources,Set.of("result"),
            model,scope,AnalysisEvidenceSpillStore.disabled(),()->{});
        assertThat(result.value()).containsEntry("status","SUPPORTED");
        assertThat(result.modelCalls()).isEqualTo(1);
        verify(model).chat(argThat((String prompt)->prompt.contains("The document discusses maintenance.")
            && !prompt.contains("RAW_SOURCE_MUST_NOT_REACH_REVIEW")));
    }
    @Test void unsupportedHealthConclusionIsNotAdmitted() {
        var model=mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"status\":\"UNSUPPORTED\",\"reason\":\"A count does not establish overall health\",\"unsupportedClaims\":[\"Overall health\"]}");
        var result=new ComputedReportAdmission().review("Analyze observations","The system is always healthy.",
            Map.of("result",new Dataset("result",Map.of(),List.of(Map.of("count",0)))),Set.of("result"),
            model,scope,AnalysisEvidenceSpillStore.disabled(),()->{});
        assertThat(result.value()).containsEntry("status","UNSUPPORTED");
    }
    @Test void malformedDecisionCannotCertifyAReport() {
        var model=mock(ChatModel.class);when(model.chat(anyString())).thenReturn("{\"status\":\"SUPPORTED\",\"reason\":\"bad\",\"unsupportedClaims\":[\"Still unsupported\"]}");
        assertThat(new ComputedReportAdmission().review("Q","R",Map.of(),Set.of(),model,scope,AnalysisEvidenceSpillStore.disabled(),()->{}).value())
            .containsEntry("status","INCONCLUSIVE");
    }
    @Test void modelBudgetExceptionRemainsAControlSignal() {
        var model=mock(ChatModel.class);when(model.chat(anyString())).thenThrow(new com.chatchat.agents.orchestration.model.AgentBudgetExceededException("limit"));
        assertThatThrownBy(()->new ComputedReportAdmission().review("Q","R",Map.of(),Set.of(),model,scope,AnalysisEvidenceSpillStore.disabled(),()->{}))
            .isInstanceOf(com.chatchat.agents.orchestration.model.AgentBudgetExceededException.class);
    }
    @Test void checkpointStorageFailureIsNotMisreportedAsInvalidModelOutput() {
        var model=mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"status\":\"SUPPORTED\",\"reason\":\"ok\",\"unsupportedClaims\":[]}");
        var store=mock(AnalysisEvidenceSpillStore.class);
        when(store.readCheckpoint(any(),anyString(),anyString())).thenReturn(Optional.empty());
        doAnswer(call->{
            String key=call.getArgument(1);
            if(!key.endsWith(":input"))throw new IllegalStateException("storage fault");
            return null;
        }).when(store).checkpoint(any(),anyString(),anyString(),anyString());
        assertThatThrownBy(()->new ComputedReportAdmission().review("Q","R",Map.of(),Set.of(),model,scope,store,()->{}))
            .isInstanceOf(IllegalStateException.class).hasMessage("storage fault");
    }
}
