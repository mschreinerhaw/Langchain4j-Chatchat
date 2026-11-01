package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.tool.*;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.routing.McpToolRouter;
import com.chatchat.common.tool.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlanToolGovernanceTest {
    @ParameterizedTest
    @CsvSource({"medium,low", "high,low", "low,high", "low,medium"})
    @SuppressWarnings("unchecked")
    void publishedToolRiskCannotBeReclassifiedByTheOverallModelIntent(String intentRisk, String toolRisk) throws Exception {
        var service=mock(ToolRuntimeService.class, CALLS_REAL_METHODS);
        var runtime=new InterpretationPlanRuntime(service,new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class));
        var step=new InterpretationPlan.Step(1,"mcp_tool","published_query",Map.of(),List.of(),null,null);
        var plan=new InterpretationPlan("1.0",new InterpretationPlan.Intent("analysis","Analyze authorized data",intentRisk),
            null,new InterpretationPlan.Plan(List.of(step)),null,null);
        var request=new InterpretationPlanRuntime.ExecutionRequest(plan,mock(ToolRegistry.class),List.of("published_query"),
            "tenant","request","conversation","user",Map.of());
        var attributes=InterpretationPlanRuntime.class.getDeclaredMethod("attributesForStep",
            InterpretationPlanRuntime.ExecutionRequest.class,InterpretationPlan.Step.class,Map.class,Map.class,
            McpToolRouter.RoutingDecision.class);
        attributes.setAccessible(true);
        var actual=(Map<String,Object>)attributes.invoke(runtime,request,step,Map.of(),Map.of(),null);
        var execution=(Map<String,Object>)actual.get("executionPlan");
        assertThat(execution).containsEntry("intentRiskLevel",intentRisk).doesNotContainKeys("risk_level","riskLevel");
        var build=ToolRuntimeService.class.getDeclaredMethod("buildExecutionPlan",String.class,ToolRuntimeRequest.class,
            ToolMetadata.class,ToolInput.class);
        build.setAccessible(true);
        var authoritative=(ToolExecutionPlan)build.invoke(service,"published_query",
            ToolRuntimeRequest.builder().toolName("published_query").attributes(actual).build(),
            ToolMetadata.builder().id("published_query").riskLevel(toolRisk).operationType("execute").build(),new ToolInput());
        assertThat(authoritative.riskLevel()).isEqualTo(toolRisk);
    }
}
