package com.chatchat.agents.orchestration;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.knowledge.runtime.KnowledgeContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentPlannerKnowledgeContinuationTest {

    @Test
    void doesNotDelegateKnowledgeEvidenceExpansionToTheModel() {
        AgentPlanner planner = new AgentPlanner(mock(ToolRegistry.class), new ObjectMapper());
        AtomicReference<String> prompt = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public String chat(String message) {
                prompt.set(message);
                return """
                    {
                      "version":"1.0",
                      "intent":{"type":"document_answer","goal":"explain installation","risk_level":"low"},
                      "context":{"key_facts":[],"assumptions":[],"missing_info":[],"constraints":[]},
                      "plan":{"steps":[
                        {"id":1,"action_type":"final_answer","tool_name":"",
                         "input":{"answer":"installation steps from completed evidence"},"depends_on":[]}
                      ]},
                      "execution_policy":{"max_steps":1,"allow_parallel":false,"deny_tool":[]},
                      "review":{"self_check":{"completeness_score":1.0,"hallucination_risk":0.0,
                        "tool_sufficiency":true,"missing_steps":[]},"fallback_plan":[]}
                    }
                    """;
            }
        };
        Map<String, Object> knowledge = Map.of(
            "used", true,
            "truncated", false,
            "completionState", "COMPLETE",
            "compiledContext", "complete installation procedure",
            "activatedSkills", List.of(Map.of("skillType", "PROCEDURE_LOOKUP")),
            "sources", List.of(Map.of("documentId", "doc-1", "section", "Install")));

        PlannerExecutionResult result = planner.decideNextAction(
            model, "explain installation", "", List.of(), List.of(), List.of("doc-1"), List.of(),
            List.of(), false, false, "document_search", null,
            Map.of("plannerMaxRepairAttempts", 1, KnowledgeContext.RUNTIME_ATTRIBUTE, knowledge));

        assertThat(prompt.get())
            .contains("complete installation procedure")
            .doesNotContain("continuationRequired", "Plan an authorized document evidence retrieval");
        assertThat(result.plan().valid()).isTrue();
        assertThat(result.decision().action()).isEqualTo("final");
    }
}
