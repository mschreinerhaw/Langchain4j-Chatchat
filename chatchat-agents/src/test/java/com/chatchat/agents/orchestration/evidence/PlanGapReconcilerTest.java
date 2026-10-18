package com.chatchat.agents.orchestration.evidence;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class PlanGapReconcilerTest {
    @Test void closesOnlyFreshlyReviewedGapWithSuccessfulDependencyAndKeepsSemanticGap() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {"sufficient":false,"resolutions":[{"gapId":"gap-0","status":"RESOLVED","supportingStepIds":[2],"reason":"Query now executed"},
            {"gapId":"gap-1","status":"OPEN","supportingStepIds":[],"reason":"Longer observation is still missing"}]}
            """);
        var evidence = List.<Map<String,Object>>of(Map.of("stepId",1,"success",true,"missingEvidence",List.of("query not executed","missing history")),
            Map.of("stepId",2,"success",true,"reviewReason","Execution returned complete observations"));
        var result = new PlanGapReconciler().reconcile(model,"generic goal",plan(),evidence,
            List.of("query not executed","missing history"),()->false);
        assertEquals(List.of("query not executed"),result.closed());
        assertFalse(result.sufficient());
    }
    @Test void unknownOrFailedSupportCannotCloseGap() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {"sufficient":true,"resolutions":[{"gapId":"gap-0","status":"RESOLVED","supportingStepIds":[99],"reason":"guess"}]}
            """);
        var evidence = List.<Map<String,Object>>of(Map.of("stepId",1,"success",true,"missingEvidence",List.of("gap")),Map.of("stepId",2,"success",true));
        assertTrue(new PlanGapReconciler().reconcile(model,"goal",plan(),evidence,List.of("gap"),()->false).closed().isEmpty());
    }
    @Test void equalTextFromAnUnrelatedStepRemainsOpen() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {"sufficient":true,"resolutions":[{"gapId":"gap-0","status":"RESOLVED","supportingStepIds":[2],"reason":"first gap closed"}]}
            """);
        var evidence = List.<Map<String,Object>>of(Map.of("stepId",1,"success",true,"missingEvidence",List.of("gap")),
            Map.of("stepId",2,"success",true),Map.of("stepId",3,"success",true,"missingEvidence",List.of("gap")));
        assertTrue(new PlanGapReconciler().reconcile(model,"goal",plan(),evidence,List.of("gap","gap"),()->false).closed().isEmpty());
    }
    private InterpretationPlan plan() {
        return new InterpretationPlan("1",null,null,new InterpretationPlan.Plan(List.of(
            new InterpretationPlan.Step(1,"mcp_tool","discover",Map.of(),List.of(),null,null),
            new InterpretationPlan.Step(2,"mcp_tool","execute",Map.of(),List.of(1),null,null))),null,null);
    }
}
