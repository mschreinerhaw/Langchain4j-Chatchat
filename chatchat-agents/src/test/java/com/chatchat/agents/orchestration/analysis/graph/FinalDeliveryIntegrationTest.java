package com.chatchat.agents.orchestration.analysis.graph;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.nodes.synthesis.FinalSynthesisNode;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisSummaryGovernanceCoordinator;
import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.runtime.answer.AnswerCandidateCollector;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.protocol.ModelProtocolJson;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
public class FinalDeliveryIntegrationTest {
    private record Accepted(ChatModel model,Map<String,Object> metadata,List<Map<String,Object>> events){}
    private Accepted accepted(Object legacyPublication) {
        var model=mock(ChatModel.class);var metadata=new LinkedHashMap<String,Object>();
        metadata.put("modelAnalysisProtocol","model_native_analysis.v2");
        metadata.put("harnessLegacyTextTransport",true);
        var finalResponse=new LinkedHashMap<String,Object>();
        finalResponse.put("schemaVersion","model_native_analysis.v2");finalResponse.put("decision",Map.of("action","COMPLETE"));
        finalResponse.put("output_type","final");finalResponse.put("content","Updated final answer");
        if(legacyPublication!=null)finalResponse.put("publication",legacyPublication);
        when(model.chat(anyString())).thenReturn(ModelProtocolJson.compact(Map.of("schemaVersion","model_native_analysis.v2",
            "decision",Map.of("action","CONTINUE"),"output_type","intermediate","content","Previous draft",
            "plan",HarnessProtocolBoundaryTest.plan(),
            "evidenceRequests",List.of(Map.of("operation","READ_TEXT","datasetReference","source","record",1,
                "field","text","fromChar",0,"maxChars",20)))),ModelProtocolJson.compact(finalResponse));
        var events=new ArrayList<Map<String,Object>>();
        new ModelNativeAnalysisHarness(3).execute("Analyze authorized data",List.of(new Dataset("source",Map.of(),
            List.of(Map.of("text","Bound template result")))),model,
            GovernanceIsolationScope.runtime("tenant","user","run","request","session"),AnalysisEvidenceSpillStore.disabled(),
            metadata,()->{},events::add);
        return new Accepted(model,metadata,events);
    }
    private FinalSynthesisNode.FinalSynthesisResult deliver(Accepted accepted) {
        var adapter=mock(AgentRunResultAdapter.class);
        var node=new FinalSynthesisNode(adapter,"agentRunId",new AnalysisSummaryGovernanceCoordinator(adapter,"agentRunId"),new AnswerCandidateCollector());
        return node.synthesizeFinal(new FinalSynthesisNode.FinalModelSynthesisRequest(accepted.model(),"unused","completed",
            "run",1,1,0,0,0,true,true,true,0,0,List.of(),List.of(),Map.of("agentRunId","run"),accepted.metadata()));
    }
    @Test void updatedFinalDeliversWithStaleOmittedOrMalformedLegacyReferences() {
        for(Object legacy:Arrays.asList(null,Map.of("reportSha256",ModelProtocolJson.sha256Hex("Previous draft"),
            "evidenceSnapshotRef","old-snapshot"),Map.of(),"legacy-placeholder")) {
            var accepted=accepted(legacy);
            var binding=(Map<?,?>)accepted.metadata().get("modelPublicationRequest");
            assertThat(binding.get("authority")).isEqualTo("RUNTIME");
            assertThat(binding.get("reportSha256")).isEqualTo(ModelProtocolJson.sha256Hex("Updated final answer"));
            assertThat(deliver(accepted).content()).isEqualTo("Updated final answer");
            assertThat(accepted.metadata().get("publicationState")).isEqualTo("DELIVERED");
            verify(accepted.model(),times(2)).chat(anyString());
        }
    }
    @Test void runtimeBindingStillDetectsContentChangedAfterAcceptance() {
        var accepted=accepted(Map.of());accepted.metadata().put("modelAnalysisOutput","Tampered content");
        assertThatThrownBy(()->deliver(accepted)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version binding");
    }
    @Test void modelCannotChangeDeliveryTargetAndVersionBindingIsObservable() {
        var accepted=accepted(Map.of("target","EXTERNAL","authority","MODEL"));
        assertThat(((Map<?,?>)accepted.metadata().get("modelPublicationRequest")).get("target")).isEqualTo("CURRENT_SESSION");
        assertThat(deliver(accepted).generated()).isTrue();
        assertThat(accepted.events()).anySatisfy(event->{
            assertThat(event.get("publicationState")).isEqualTo("REQUESTED");
            assertThat(event.get("reportSha256")).isEqualTo(ModelProtocolJson.sha256Hex("Updated final answer"));
            assertThat(event.get("evidenceSnapshotRef")).isEqualTo(accepted.metadata().get("modelEvidenceSnapshotRef"));
        });
    }
}
