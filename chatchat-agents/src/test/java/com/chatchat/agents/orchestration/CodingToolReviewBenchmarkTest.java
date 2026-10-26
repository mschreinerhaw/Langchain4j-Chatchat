package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.orchestration.analysis.context.ContextTokenEstimator;
import com.chatchat.agents.protocol.ModelProtocolJson;
import dev.langchain4j.model.chat.ChatModel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local routing comparison only: stubbed provider, never a production latency/correctness claim. */
class CodingToolReviewBenchmarkTest {
    @Test void sameDirectEvidenceUsesOneLegacyModelCallAndZeroCodingModelCalls() throws Exception {
        var fixture=new ToolReviewerOptimizationTest();
        var model=mock(ChatModel.class);
        var calls=new AtomicInteger(); var promptTokens=new ArrayList<Long>();
        when(model.chat(anyString())).thenAnswer(call -> {
            calls.incrementAndGet(); promptTokens.add(new ContextTokenEstimator().estimate(call.getArgument(0,String.class)).tokens());
            return "{\"satisfied\":true,\"reason\":\"fixture only\"}";
        });
        // No discovery role: this compares result admission rather than authorized candidate selection.
        var execution=new InterpretationPlanRuntime.StepExecution(1,"mcp_tool","fixture_direct",true,
            Map.of("rows",List.of(Map.of("amount","100.10"),Map.of("amount","53.07"))),null,null,null,0L);
        var request=new InterpretationPlanRuntime.StepReviewRequest(null,null,execution,Map.of(),1,1);
        var engine=fixture.engine(model);
        var rows=new ArrayList<Map<String,Object>>();
        for (boolean coding : List.of(false,true)) {
            int before=calls.get(); long start=System.nanoTime();
            var decision=engine.reviewInterpretationPlanToolResult(model,"Analyze the delivered amounts",null,()->false,
                request,coding?Map.of("workflowFamily","DATA_ANALYSIS"):Map.of());
            rows.add(Map.of("mode",coding?"CODING":"LEGACY_COMPATIBILITY", "modelCalls",calls.get()-before,
                "orchestrationWallMs",(System.nanoTime()-start)/1_000_000.0,"accepted",decision.satisfied()));
        }
        assertThat(rows.get(0).get("modelCalls")).isEqualTo(1);
        assertThat(rows.get(1).get("modelCalls")).isEqualTo(0);
        assertThat(rows).allMatch(row -> Boolean.TRUE.equals(row.get("accepted")));
        var report=new LinkedHashMap<String,Object>();
        report.put("measurement","LOCAL_ROUTING_WITH_STUB_PROVIDER");report.put("samples",rows);
        report.put("legacyEstimatedInputTokens",promptTokens.stream().mapToLong(Long::longValue).sum());
        report.put("codingReviewerInputTokens",0);report.put("codingReviewerOutputTokens",0);
        report.put("businessCorrectness","NOT_MEASURED");
        var path=Path.of("target/runtime-review-cleanup/reviewer-benchmark.json");Files.createDirectories(path.getParent());
        Files.writeString(path,ModelProtocolJson.compact(report));
    }
}
