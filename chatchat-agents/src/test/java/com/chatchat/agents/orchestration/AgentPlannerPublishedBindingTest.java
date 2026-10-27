package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.PublishedTemplateSupplyTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentPlannerPublishedBindingTest {
    @Test void plannerAndExecutorUseTheSamePublishedCapabilityDependencyValidation() throws Exception {
        var registry=PublishedTemplateSupplyTest.registry(false);
        var json=new ObjectMapper();
        var model=mock(dev.langchain4j.model.chat.ChatModel.class);
        when(model.chat(anyString())).thenReturn(json.writeValueAsString(PublishedTemplateSupplyTest.plan()));
        String child="published_inspection", bridge="capability_bridge", execute="template_executor";
        var dag=List.of(Map.of("tool",bridge,"dependsOnTools",List.of()),
            Map.of("tool",execute,"dependsOnTools",List.of(bridge)));
        var decision=new AgentPlanner(registry,json).decideNextAction(model,"Inspect authorized data","",
            List.of(child,bridge,execute),List.of(),List.of(),List.of(),List.of(bridge,execute),true,false,null,null,
            Map.of("authoritativeWorkflowDag",dag,"plannerMaxRepairAttempts",1));
        assertThat(decision.plan().valid()).as(decision.decision().executionPlan().toString()).isTrue();
        verify(model,times(1)).chat(anyString());
    }
}
