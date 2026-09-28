package com.chatchat.chat.skills.domain;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.skills.DomainSkillRuntimePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainSkillPlanningRouterTest {

    @Test
    void modelSelectsAuthorizedSkillsAndFusesBoundedPlanningKnowledge() {
        ChatModel model = mock(ChatModel.class);
        ModelResourceRegistry resources = mock(ModelResourceRegistry.class);
        when(resources.defaultChatModel()).thenReturn("planner-model");
        when(model.chat(anyString())).thenReturn("""
            {"activatedSkillIds":["risk","not-authorized","market"],
             "principles":["先验证数据日期"],
             "analysisDimensions":["市场表现","风险"],
             "requiredEvidence":["指数收盘值"],
             "constraints":["不得编造行情"],
             "validationRules":["结论必须关联证据"]}
            """);
        DomainSkillCompilerProperties properties = new DomainSkillCompilerProperties();
        properties.setMaxActivatedSkills(2);
        DomainSkillPlanningRouter router = new DomainSkillPlanningRouter(
            model, new ObjectMapper(), resources, mock(ConfigurableChatModelFactory.class), properties);
        var risk = new DomainSkillRuntimePort.DomainSkillContent(
            "risk", "证券风险", "风险管理", "FULL_RISK_SKILL_TEXT");
        var market = new DomainSkillRuntimePort.DomainSkillContent(
            "market", "市场复盘", "市场行情", "FULL_MARKET_SKILL_TEXT");

        DomainSkillPlanningRouter.RoutingResult result = router.route(
            "生成收盘分析", "planner-model", List.of(risk, market));

        assertThat(result.status()).isEqualTo("MODEL_ROUTED");
        assertThat(result.activated()).extracting(DomainSkillRuntimePort.DomainSkillContent::id)
            .containsExactly("risk", "market");
        assertThat(result.compiledContext())
            .contains("先验证数据日期", "指数收盘值", "不得编造行情")
            .doesNotContain("FULL_RISK_SKILL_TEXT", "FULL_MARKET_SKILL_TEXT", "not-authorized");
        assertThat(router.projection(result))
            .containsEntry("schemaVersion", "domain_skill_planning.v2")
            .containsEntry("selectedCount", 2)
            .containsEntry("activatedCount", 2)
            .containsEntry("loadedCount", 2);
        verify(model).chat(anyString());
    }

    @Test
    void routingFailureFailsClosedWithoutInjectingEverySelectedSkill() {
        ChatModel model = mock(ChatModel.class);
        ModelResourceRegistry resources = mock(ModelResourceRegistry.class);
        when(resources.defaultChatModel()).thenReturn("planner-model");
        when(model.chat(anyString())).thenThrow(new IllegalStateException("model unavailable"));
        DomainSkillPlanningRouter router = new DomainSkillPlanningRouter(
            model, new ObjectMapper(), resources, mock(ConfigurableChatModelFactory.class),
            new DomainSkillCompilerProperties());

        DomainSkillPlanningRouter.RoutingResult result = router.route("分析风险", "planner-model", List.of(
            new DomainSkillRuntimePort.DomainSkillContent("risk", "证券风险", "风险管理", "FULL_TEXT")));

        assertThat(result.status()).isEqualTo("ROUTING_FAILED");
        assertThat(result.activated()).isEmpty();
        assertThat(result.compiledContext()).isEmpty();
    }
}
