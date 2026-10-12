package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HarnessProtocolBoundaryTest {
    static Map<String,Object> plan() {
        return Map.of("version","1.0", "intent",Map.of("type","analysis","goal","Analyze delivered template evidence"),
            "context",Map.of("constraints",List.of("Delivered evidence only")),
            "plan",Map.of("steps",List.of(Map.of("id",1,"action_type","reasoning","depends_on",List.of(),
                "input",Map.of("objective","Check evidence", "required_evidence",List.of("source"),"expected_output","Report")))));
    }
    private Map<String,Object> turn(String action, boolean planned, List<Map<String,Object>> requests) {
        var value=new LinkedHashMap<String,Object>();value.put("schemaVersion","model_native_analysis.v2");
        value.put("decision",Map.of("action",action));value.put("output_type",action.equals("CONTINUE")?"intermediate":"final");
        value.put("content",action.equals("CONTINUE")?"Progress":"Final answer");value.put("evidenceRequests",requests);
        if(planned)value.put("plan",plan());return value;
    }
    private Map<String,Object> read() {return Map.of("operation","READ_TEXT","datasetReference","source","record",1,"field","text","fromChar",0,"maxChars",20);}
    private record Run(Map<String,Object> metadata,List<Map<String,Object>> events,List<String> prompts,ModelNativeAnalysisHarness.Result result) {}
    @SafeVarargs private final Run run(Map<String,Object>... responses) {
        var meta=new LinkedHashMap<String,Object>();var events=new ArrayList<Map<String,Object>>();var prompts=new ArrayList<String>();
        var model=mock(ChatModel.class);int[] index={0};
        when(model.chat(any(ChatRequest.class))).thenAnswer(call->{
            ChatRequest request=call.getArgument(0);assertThat(request.messages().get(0)).isInstanceOf(SystemMessage.class);
            assertThat(request.messages().get(1)).isInstanceOf(UserMessage.class);
            prompts.add(((SystemMessage)request.messages().get(0)).text()+"\n"+((UserMessage)request.messages().get(1)).singleText());
            return ChatResponse.builder().aiMessage(AiMessage.from(ModelProtocolJson.compact(responses[index[0]++]))).build();
        });
        var result=new ModelNativeAnalysisHarness(responses.length).execute("Analyze scoped evidence",
            List.of(new Dataset("source",Map.of(),List.of(Map.of("text","Original template data")))),model,
            GovernanceIsolationScope.runtime("tenant","user","run","request","session"),AnalysisEvidenceSpillStore.disabled(),
            meta,()->{},events::add);
        return new Run(meta,events,prompts,result);
    }
    @Test void missingPlanBlocksReadsAndProvidesFeedback() {
        var run=run(turn("CONTINUE",false,List.of(read())),turn("COMPLETE",true,List.of()));
        assertThat(run.prompts().get(1)).contains("PLAN_REQUIRED");
        assertThat(run.events()).noneSatisfy(event->assertThat(event.get("stage")).isEqualTo("EVIDENCE_REQUEST_STARTED"));
        assertThat(run.result().markdown()).isEqualTo("Final answer");
    }
    @Test void sameTurnSavesPlanBeforeReadingAndOrdinaryCallsReuseIt() {
        var run=run(turn("CONTINUE",true,List.of(read())),turn("CONTINUE",false,List.of(read())),turn("COMPLETE",false,List.of()));
        var stages=run.events().stream().map(e->e.get("stage")).toList();
        assertThat(stages.indexOf("PLAN_RECORDED")).isLessThan(stages.indexOf("EVIDENCE_REQUEST_STARTED"));
        assertThat(stages.stream().filter("PLAN_RECORDED"::equals).count()).isEqualTo(1);
        assertThat(run.result().markdown()).isEqualTo("Final answer");
    }
    @Test void externalDispatchIsUnavailableAndExplicitlyRejected() {
        var run=run(turn("CONTINUE",true,List.of(Map.of("operation","CALL_TOOL","toolName","asset_discovery","input",Map.of()))),
            turn("COMPLETE",false,List.of()));
        assertThat(run.prompts().get(1)).contains("TEMPLATE_SCOPE_LIMIT","REQUEST_REJECTED");
        assertThat(run.result().markdown()).isEqualTo("Final answer");
    }
    @Test void finalWithRequestsCannotExecuteOrReplaceAcceptedState() {
        var run=run(turn("COMPLETE",true,List.of(read())),turn("COMPLETE",true,List.of()));
        assertThat(run.prompts().get(1)).contains("Only CONTINUE may execute");
        assertThat(run.events()).noneSatisfy(event->assertThat(event.get("stage")).isEqualTo("EVIDENCE_REQUEST_STARTED"));
    }
    @Test void unsupportedWaitProducesFeedbackWithoutStateTransition() {
        var run=run(turn("WAIT",true,List.of()),turn("COMPLETE",true,List.of()));
        assertThat(run.prompts().get(1)).contains("UNSUPPORTED_ACTION");
        assertThat(run.metadata().get("executionState")).isEqualTo("COMPLETED");
    }
    @Test void budgetStopPreservesAuthoredContentAndDeclaration() {
        var run=run(turn("CONTINUE",true,List.of(read())));
        assertThat(run.result().markdown()).isEqualTo("Progress");
        assertThat(run.metadata()).containsEntry("executionState","STOPPED");
        assertThat(((Map<?,?>)run.metadata().get("modelOutput")).get("type")).isEqualTo("INTERMEDIATE");
        assertThat(run.metadata().get("modelDecision").toString()).contains("CONTINUE");
    }
}
