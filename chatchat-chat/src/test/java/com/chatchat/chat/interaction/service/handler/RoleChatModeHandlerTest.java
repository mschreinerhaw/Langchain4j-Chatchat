package com.chatchat.chat.interaction.service.handler;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.common.knowledge.KnowledgeContext;
import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeRuntimePort;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleChatModeHandlerTest {

    @Test
    void answersTableProductQuestionAsRoleWithoutEnteringAnyToolPath() {
        ChatModel defaultModel = mock(ChatModel.class);
        ChatModel boundModel = mock(ChatModel.class);
        ConfigurableChatModelFactory modelFactory = mock(ConfigurableChatModelFactory.class);
        SkillCatalogService skillCatalog = mock(SkillCatalogService.class);
        KnowledgeRuntimePort knowledgeRuntime = mock(KnowledgeRuntimePort.class);
        RoleChatModeHandler handler = new RoleChatModeHandler(
            defaultModel, modelFactory, skillCatalog, knowledgeRuntime);

        SkillDefinition role = new SkillDefinition(
            "table-product-advisor",
            "数据表产品顾问",
            "帮助业务用户理解数据产品的适用信息与业务场景",
            List.of("数据产品说明", "业务使用建议"),
            List.of("数据产品", "业务咨询"),
            "role_chat",
            "role-model",
            "回答面向业务用户，不要重点解释表结构、字段类型或底层技术实现。",
            null,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
            Map.of(), null, null, List.of(), "published", false);
        when(skillCatalog.resolve("table-product-advisor")).thenReturn(role);
        when(modelFactory.create("role-model")).thenReturn(boundModel);
        when(boundModel.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn(
            """
            这张表主要反映客户在某一统计日期的股票期权证券持仓及盈亏快照。
            它可支持客户画像、持仓结构分析、盈亏识别和业务人员跟进。用户通常可据此筛选重点客户、了解风险与收益状态并准备客户沟通。
            当问题关注指定统计日的客户最新持仓及盈亏情况时，应优先使用这张表。
            例如：1. 筛选期权持仓亏损较大的客户；2. 分析某日客户持仓集中情况；3. 为客户回访准备持仓与盈亏概览。
            表名虽含“流水”，但简介将其定义为最新持仓快照；若要查询盘中实时行情、历史逐笔交易或完整收益归因，这张表不适合，应改用行情、成交明细或收益归因数据。
            """
        );

        String question = """
            你正在帮助用户理解当前数据表产品。
            当前数据表：名称：股票期权证券盈亏流水
            业务分类：两融业务人员
            产品简介：保存客户某一统计日期最新持仓数据，作为客户画像及分析基础数据。
            请从业务使用角度回答主要信息、业务场景、优先使用条件和 2～3 个示例。
            如果当前表不适合用户需求，要明确说明。
            """;
        InteractionResponse response = handler.handle(
            InteractionRequest.builder()
                .mode("role_chat")
                .skillId("table-product-advisor")
                .query(question)
                .build(),
            InteractionContext.builder()
                .requestId("request-role-1")
                .conversationId("conversation-role-1")
                .mode(InteractionMode.ROLE_CHAT)
                .history(List.of())
                .build());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(boundModel).chat(prompt.capture());
        verify(defaultModel, never()).chat(org.mockito.ArgumentMatchers.anyString());
        verify(knowledgeRuntime, never()).retrieveKnowledge(org.mockito.ArgumentMatchers.any());

        assertThat(prompt.getValue())
            .contains("ROLE_CHAT mode", "Do not plan, select", "数据表产品顾问")
            .contains("股票期权证券盈亏流水", "不要重点解释表结构")
            .contains("If the supplied subject is unsuitable");
        assertThat(response.getToolTraces()).isEmpty();
        assertThat(response.getMetadata())
            .containsEntry("executionMode", "ROLE_CHAT")
            .containsEntry("toolPlanningSkipped", true)
            .containsEntry("knowledgeRetrieval", "not_configured");
        assertThat(response.getAnswer())
            .contains("持仓及盈亏快照", "客户画像", "应优先使用", "例如")
            .contains("表名虽含“流水”", "这张表不适合", "实时行情", "历史逐笔交易");
    }
    @Test
    void retrievesKnowledgeOnlyInsideTheDocumentsBoundToTheRole() {
        ChatModel defaultModel = mock(ChatModel.class);
        ConfigurableChatModelFactory modelFactory = mock(ConfigurableChatModelFactory.class);
        SkillCatalogService skillCatalog = mock(SkillCatalogService.class);
        KnowledgeRuntimePort knowledgeRuntime = mock(KnowledgeRuntimePort.class);
        RoleChatModeHandler handler = new RoleChatModeHandler(
            defaultModel, modelFactory, skillCatalog, knowledgeRuntime);
        SkillDefinition role = mock(SkillDefinition.class);
        when(role.id()).thenReturn("document-role");
        when(role.defaultMode()).thenReturn("role_chat");
        when(role.boundDocumentIds()).thenReturn(List.of("doc-options", "doc-policy"));
        when(role.boundDocumentTags()).thenReturn(List.of());
        when(skillCatalog.resolve("document-role")).thenReturn(role);
        when(knowledgeRuntime.retrieveKnowledge(org.mockito.ArgumentMatchers.any())).thenReturn(
            new KnowledgeContext(KnowledgeContext.SCHEMA_VERSION, null, List.of(),
                "股票期权业务知识上下文", List.of(), 20, 1200, false, "used"));
        when(defaultModel.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("基于内部知识的回答");

        InteractionResponse response = handler.handle(
            InteractionRequest.builder()
                .mode("role_chat")
                .skillId("document-role")
                .query("股票期权业务如何使用？")
                .tenantId("tenant-a")
                .userId("user-a")
                .build(),
            InteractionContext.builder()
                .requestId("request-role-docs")
                .conversationId("conversation-role-docs")
                .mode(InteractionMode.ROLE_CHAT)
                .history(List.of())
                .build());

        ArgumentCaptor<KnowledgeRequest> searchRequest = ArgumentCaptor.forClass(KnowledgeRequest.class);
        verify(knowledgeRuntime).retrieveKnowledge(searchRequest.capture());
        assertThat(searchRequest.getValue().scope().documentIds()).containsExactly("doc-options", "doc-policy");
        assertThat(searchRequest.getValue().scope().tenantId()).isEqualTo("tenant-a");
        assertThat(searchRequest.getValue().maxTokens()).isEqualTo(1200);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(defaultModel).chat(prompt.capture());
        assertThat(prompt.getValue()).contains("<domain_knowledge>", "</domain_knowledge>");
        assertThat(response.getMetadata())
            .containsEntry("knowledgeRetrieval", "used")
            .containsEntry("knowledgeUsed", true)
            .containsEntry("knowledgeTokenBudget", 1200);
        assertThat(response.getToolTraces()).isEmpty();
    }
}
