package com.chatchat.agents.orchestration;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentOrchestratorTest {

    @Test
    void revisesFinalAnswerWhenReviewerRejectsIt() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"final\",\"answer\":\"建议查询部署文档中的 MySQL 章节。\"}",
            "{\"accepted\":false,\"feedback\":\"没有直接回答初始化步骤\",\"revisedAnswer\":\"LiveData 数据库初始化应先创建 MySQL 库和账号，再导入 schema.sql，最后配置服务数据源并启动验证。\"}"
        );
        AgentOrchestrator orchestrator = newOrchestrator(chatModel);

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "如何初始化 LiveData 数据库",
            List.of(),
            "你是 LiveData Studio 运维助手。",
            null,
            List.of(),
            List.of(),
            "livedata_ops",
            "req-1",
            "conv-1",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).contains("导入 schema.sql");
        assertThat(result.metadata())
            .containsEntry("answerReviewStatus", "revised")
            .containsEntry("answerReviewFeedback", "没有直接回答初始化步骤");
    }

    @Test
    void keepsFinalAnswerWhenReviewerAcceptsIt() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"final\",\"answer\":\"初始化步骤：创建数据库、执行 schema.sql、配置连接串、重启服务并检查健康接口。\"}",
            "{\"accepted\":true,\"feedback\":\"回答直接覆盖用户需求\",\"revisedAnswer\":\"\"}"
        );
        AgentOrchestrator orchestrator = newOrchestrator(chatModel);

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "如何初始化 LiveData 数据库",
            List.of(),
            "你是 LiveData Studio 运维助手。",
            null,
            List.of(),
            List.of(),
            "livedata_ops",
            "req-2",
            "conv-2",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).isEqualTo("初始化步骤：创建数据库、执行 schema.sql、配置连接串、重启服务并检查健康接口。");
        assertThat(result.metadata())
            .containsEntry("answerReviewStatus", "accepted")
            .containsEntry("answerReviewFeedback", "回答直接覆盖用户需求");
    }

    private AgentOrchestrator newOrchestrator(ChatModel chatModel) {
        return new AgentOrchestrator(
            chatModel,
            mock(ToolRegistry.class),
            new ObjectMapper(),
            new ModelsConfig()
        );
    }

    private static final class QueueChatModel implements ChatModel {
        private final Queue<String> responses = new ArrayDeque<>();

        private QueueChatModel(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public String chat(String message) {
            assertThat(message).isNotBlank();
            assertThat(responses).isNotEmpty();
            return responses.remove();
        }
    }
}
