package com.chatchat.agents.orchestration.analysis.nodes.synthesis;
import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisSummaryGovernanceCoordinator;
import com.chatchat.agents.runtime.answer.AnswerCandidateCollector;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class FinalSynthesisNodeTest {
    private final AgentRunResultAdapter adapter=mock(AgentRunResultAdapter.class);
    private final ChatModel model=mock(ChatModel.class);
    private FinalSynthesisNode node(){return new FinalSynthesisNode(adapter,"agentRunId",new AnalysisSummaryGovernanceCoordinator(adapter,"agentRunId"),new AnswerCandidateCollector());}
    private FinalSynthesisNode.FinalModelSynthesisRequest request(Map<String,Object> metadata){return new FinalSynthesisNode.FinalModelSynthesisRequest(model,"Answer the question", "completed","run",1,1,0,0,0,true,true,true,0,0,List.of(),List.of(),Map.of("agentRunId","run"),metadata);}
    @Test void modelDraftPublishesUnchangedWithoutAnotherModelReviewerOrMandatoryCharts(){
        var metadata=new LinkedHashMap<String,Object>();metadata.put("modelNativeReportDraft","# Findings\nThe model chooses the analysis.");
        var result=node().synthesizeFinal(request(metadata));
        assertThat(result.generated()).isTrue();assertThat(result.content()).isEqualTo("# Findings\nThe model chooses the analysis.");
        assertThat(metadata).containsEntry("analysisSemanticReviewPolicy","NONE_USER_JUDGES").doesNotContainKeys("analysisDriverModelInvoked","visualizationPlanning","analysisDriverReview");
        assertThat(((Map<?,?>)metadata.get("analysisReportContract")).get("reportType")).isEqualTo("MODEL_REPORT");
        verifyNoInteractions(model);
    }
    @Test void noDatasetRequestStillHasOneModelAuthoredReportAndNoDriverFallback(){
        when(model.chat(any(String.class))).thenReturn("Model-authored response.");
        var metadata=new LinkedHashMap<String,Object>();var result=node().synthesizeFinal(request(metadata));
        assertThat(result.content()).isEqualTo("Model-authored response.");assertThat(metadata).containsEntry("modelNativeHarnessActive",true);
        verify(model,times(1)).chat(any(String.class));
    }
    @Test void emptyModelResponseIsTechnicalFailureInsteadOfInventedReport(){
        when(model.chat(any(String.class))).thenReturn("");
        assertThatThrownBy(()->node().synthesizeFinal(request(new LinkedHashMap<>()))).isInstanceOf(IllegalStateException.class).hasMessageContaining("empty");
    }
    @Test void authorizationBoundaryStopsPublicationAndDoesNotInvokeModel(){
        var metadata=new LinkedHashMap<String,Object>();metadata.put("confirmationRequired",true);metadata.put("modelNativeReportDraft","Saved report");
        assertThat(node().synthesizeFinal(request(metadata)).generated()).isFalse();verifyNoInteractions(model);
    }
    @Test void sourceFailureIsObservableWithoutSuppressingModelAnalysis(){
        var metadata=new LinkedHashMap<String,Object>();metadata.put("modelNativeReportDraft","Analysis of available records.");
        var original=request(metadata);
        var partial=new FinalSynthesisNode.FinalModelSynthesisRequest(model,original.prompt(),"completed","run",1,1,0,10,10,false,false,false,1,0,List.of(),List.of(),original.runtimeAttributes(),metadata);
        assertThat(node().synthesizeFinal(partial).content()).isEqualTo("Analysis of available records.");
        assertThat(metadata).containsEntry("analysisGraphStatus","COMPLETED_WITH_LIMITATIONS");verifyNoInteractions(model);
    }

    private Map<String,Object> v2Metadata(String action) {
        var metadata = new LinkedHashMap<String,Object>();
        metadata.put("modelAnalysisProtocol", "model_native_analysis.v2");
        metadata.put("modelDecision", Map.of("action", action));
        metadata.put("modelNativeReportDraft", "Chosen report");
        metadata.put("modelEvidenceSnapshotRef", "snapshot");
        metadata.put("publicationState", action.equals("PUBLISH") ? "REQUESTED" : "NOT_REQUESTED");
        metadata.put("modelPublicationRequest", Map.of("reportSha256", com.chatchat.agents.protocol.ModelProtocolJson.sha256Hex("Chosen report"),
            "evidenceSnapshotRef", "snapshot"));
        return metadata;
    }
    @Test void v2DraftCompleteWaitAndPartialCompletionNeverPublish() {
        for (String action : List.of("CONTINUE", "COMPLETE", "WAIT", "PARTIAL_COMPLETE")) {
            var metadata = v2Metadata(action); var result = node().synthesizeFinal(request(metadata));
            assertThat(result.generated()).isFalse(); assertThat(result.content()).isEmpty();
            assertThat(metadata).containsEntry("modelNativeReportDraft", "Chosen report").containsEntry("publicationState", "NOT_REQUESTED");
        }
        verifyNoInteractions(model);
    }
    @Test void v2PublishesBoundVersionDespiteGapsAndPreservesPartialScope() {
        var metadata = v2Metadata("PUBLISH");
        metadata.put("modelEvidenceAssessmentAudit", Map.of("assessment", Map.of("evidenceStatus", "PARTIAL", "missingEvidence", List.of("chunk2"))));
        var result = node().synthesizeFinal(request(metadata));
        assertThat(result.content()).isEqualTo("Chosen report");
        assertThat(metadata).containsEntry("publicationState", "DELIVERED").containsEntry("analysisExecutionStatus", "PARTIALLY_COMPLETED");
        verifyNoInteractions(model);
    }
    @Test void v2AuthorizationAndReportVersionStillBlockPublication() {
        var denied = v2Metadata("PUBLISH"); denied.put("confirmationRequired", true);
        assertThat(node().synthesizeFinal(request(denied)).generated()).isFalse();
        assertThat(denied).containsEntry("publicationState", "REJECTED");
        var fatal = v2Metadata("PUBLISH"); fatal.put("fatalExecutionBlocked", true);
        assertThat(node().synthesizeFinal(request(fatal)).generated()).isFalse();
        assertThat(fatal).containsEntry("publicationState", "REJECTED");
        var changed = v2Metadata("PUBLISH"); changed.put("modelNativeReportDraft", "Changed report");
        assertThatThrownBy(() -> node().synthesizeFinal(request(changed))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version binding");
        assertThat(changed).containsEntry("publicationState", "REJECTED");
    }

}
