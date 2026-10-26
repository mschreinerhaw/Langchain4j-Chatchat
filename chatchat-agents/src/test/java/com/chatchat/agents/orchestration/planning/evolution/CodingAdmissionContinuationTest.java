package com.chatchat.agents.orchestration.planning.evolution;

import com.chatchat.agents.runtime.plan.*;
import com.chatchat.agents.runtime.plan.execution.*;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingAdmissionContinuationTest {
    @Test void durableStepReviewReceivesTheOriginalCodingOwnershipAttributes() {
        var attrs=Map.<String,Object>of("workflowFamily","DATA_ANALYSIS","tenantId","tenant");
        var step=new InterpretationPlan.Step(1,"mcp_tool","data",Map.of(),List.of(),null,null);
        var plan=new InterpretationPlan("1",null,null,new InterpretationPlan.Plan(List.of(step)),null,null);
        var continuation=new PlanExecutionContinuation(null,"session",plan,List.of(1),List.of(),List.of(),List.of(),0,
            Map.of("attributes",attrs));
        var execution=new InterpretationPlanRuntime.StepExecution(1,"mcp_tool","data",true,Map.of(),null,null,null,0L);
        var review=new InterpretationPlanRuntime.StepReviewRequest(plan,step,execution,Map.of(),1,1);
        var operations=mock(AgentPlanPhaseActivityCoordinator.Operations.class);
        try (var runtimes=mockConstruction(InterpretationPlanRuntime.class,(runtime,context) -> {
            ((InterpretationPlanRuntime.StepResultReviewer)context.arguments().get(4)).review(review);
            when(runtime.executeAdmittedWave(any(),anyList(),anyList(),anyString())).thenReturn(List.of(execution));
        })) {
            var coordinator=new AgentPlanPhaseActivityCoordinator(mock(ToolRegistry.class),mock(ToolRuntimeService.class),null,operations);
            coordinator.prepare(new PlanStepPreparationCommand(null,continuation,List.of(1),Map.of()));
            verify(operations).review(null,null,null,review,attrs);
            verify(operations,never()).review(any(),any(),any(),any());
            assertThat(runtimes.constructed()).hasSize(1);
        }
    }
}
