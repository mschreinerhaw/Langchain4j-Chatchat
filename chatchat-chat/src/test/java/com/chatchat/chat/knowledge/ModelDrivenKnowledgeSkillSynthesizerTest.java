package com.chatchat.chat.knowledge;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeScope;
import com.chatchat.common.knowledge.KnowledgeSkillType;
import com.chatchat.knowledgebase.runtime.DefaultKnowledgeSkillSynthesizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelDrivenKnowledgeSkillSynthesizerTest {

    @Test
    void createsValidatedDynamicInstancesWithinTheRuntimeBudget() {
        ChatModel model = mock(ChatModel.class);
        ConfigurableChatModelFactory factory = mock(ConfigurableChatModelFactory.class);
        when(model.chat(anyString())).thenReturn("""
            {"skills":[
              {"skillType":"METRIC_LOOKUP","domain":"securities.customer_risk","goal":"获取集中度指标口径","queryHints":["单票占比","Top3占比"],"priority":1},
              {"skillType":"RULE_LOOKUP","domain":"securities.customer_risk","goal":"获取集中度判断规则","queryHints":["高集中度"],"priority":2}
            ]}
            """);
        ModelDrivenKnowledgeSkillSynthesizer synthesizer = new ModelDrivenKnowledgeSkillSynthesizer(
            model, factory, new ObjectMapper(), new DefaultKnowledgeSkillSynthesizer());

        var plan = synthesizer.synthesize(request(Set.of(
            KnowledgeSkillType.METRIC_LOOKUP, KnowledgeSkillType.RULE_LOOKUP)));

        assertThat(plan.skills()).extracting(skill -> skill.skillType())
            .containsExactly(KnowledgeSkillType.METRIC_LOOKUP, KnowledgeSkillType.RULE_LOOKUP);
        assertThat(plan.skills()).allSatisfy(skill ->
            assertThat(skill.parameters()).containsEntry("planner", "model"));
        assertThat(plan.skills().stream().mapToInt(skill -> skill.tokenBudget()).sum()).isEqualTo(600);
    }

    @Test
    void rejectsUnknownModelCapabilitiesAndFallsBackToTheStaticWhitelist() {
        ChatModel model = mock(ChatModel.class);
        ConfigurableChatModelFactory factory = mock(ConfigurableChatModelFactory.class);
        when(model.chat(anyString())).thenReturn(
            "{\"skills\":[{\"skillType\":\"EXECUTE_ARBITRARY_CODE\",\"goal\":\"run code\"}]}");
        ModelDrivenKnowledgeSkillSynthesizer synthesizer = new ModelDrivenKnowledgeSkillSynthesizer(
            model, factory, new ObjectMapper(), new DefaultKnowledgeSkillSynthesizer());

        var plan = synthesizer.synthesize(request(Set.of(KnowledgeSkillType.RULE_LOOKUP)));

        assertThat(plan.skills()).hasSize(1);
        assertThat(plan.skills().get(0).skillType()).isEqualTo(KnowledgeSkillType.RULE_LOOKUP);
        assertThat(plan.skills().get(0).parameters()).doesNotContainKey("planner");
    }

    private KnowledgeRequest request(Set<KnowledgeSkillType> allowed) {
        return new KnowledgeRequest(
            "v", "分析客户集中度风险", "CUSTOMER_RISK_ANALYSIS", 600,
            new KnowledgeScope("risk-agent", "tenant", "user", List.of("doc-policy"),
                List.of(), List.of("securities.customer_risk")), allowed, Map.of());
    }
}
