package com.chatchat.agents.orchestration.planning.validation;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class DeclarativeStepOutputContractTest {
    private List<String> check(String action, Map<String,Object> input, String field, Boolean required, String targetAction) {
        var source = new InterpretationPlan.Step(1, action, action.equals("mcp_tool") ? "authorized-query" : null, input, List.of(), null, null);
        var target = new InterpretationPlan.Step(2, targetAction, targetAction.equals("mcp_tool") ? "authorized-detail" : null, Map.of(), List.of(1), null, null);
        var edge = new InterpretationPlan.EdgeContract(1,2,field,"number",required);
        var plan = new InterpretationPlan("1.0",null,null,new InterpretationPlan.Plan(List.of(source,target),List.of(edge)),null,null);
        List<String> issues = new ArrayList<>(); DeclarativeStepOutputContract.validate(plan,issues); return issues;
    }
    @Test void rejectsInstructionClaimingAnUncomputedDependency() {
        assertThat(check("reasoning",Map.of("instruction","Select the smallest actual source key"),"selected_key",true,"mcp_tool"))
            .singleElement().asString().contains("instruction text is not execution");
    }
    @Test void preservesNestedDeclaredValues() {
        assertThat(check("reasoning",Map.of("selection",Map.of("key",7)),"$.selection.key",true,"mcp_tool")).isEmpty();
    }
    @Test void doesNotInventOptionalValues() {
        assertThat(check("validation",Map.of(),"absent",false,"mcp_tool")).isEmpty();
    }
    @Test void executableSourceMayProduceValuesAtRuntime() {
        assertThat(check("mcp_tool",Map.of(),"actual_key",true,"mcp_tool")).isEmpty();
    }
    @Test void finalSynthesisReadsCumulativeEvidence() {
        assertThat(check("reasoning",Map.of("instruction","Explain observed results"),"summary",true,"final_answer")).isEmpty();
    }
}
