package com.chatchat.chat.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationPolicyEvaluatorTest {

    @Test
    void allowsSendOnlyWhenModelCitesExistingVerbatimEvidence() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {
              "send": true,
              "matched_conditions": ["指数涨跌幅绝对值超过2%"],
              "evidence_matches": [{
                "evidence_id": "market://2026-07-24/index",
                "fact_quote": "上证指数下跌2.3%"
              }],
              "reason": "行情波动达到通知阈值"
            }
            """);
        NotificationPolicyEvaluator evaluator = evaluator(model);

        NotificationPolicyEvaluator.Decision decision = evaluator.evaluate(
            "主要指数涨跌幅绝对值超过2%时发送",
            "今日市场波动较大。",
            List.of(Map.of(
                "sourceRef", "market://2026-07-24/index",
                "snippet", "上证指数下跌2.3%",
                "publisher", "交易所行情"
            ))
        );

        assertThat(decision.send()).isTrue();
        assertThat(decision.verified()).isTrue();
        assertThat(decision.evidenceMatches()).extracting(NotificationPolicyEvaluator.EvidenceMatch::evidenceId)
            .containsExactly("market://2026-07-24/index");
    }

    @Test
    void rejectsInventedEvidenceEvenWhenModelRequestsSend() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {
              "send": true,
              "matched_conditions": ["出现重大政策"],
              "evidence_matches": [{
                "evidence_id": "invented-evidence",
                "fact_quote": "央行发布重大政策"
              }],
              "reason": "建议发送"
            }
            """);
        NotificationPolicyEvaluator evaluator = evaluator(model);

        NotificationPolicyEvaluator.Decision decision = evaluator.evaluate(
            "出现重大政策时发送",
            "市场日报",
            List.of(Map.of("sourceRef", "web://official/1", "title", "普通市场公告"))
        );

        assertThat(decision.send()).isFalse();
        assertThat(decision.verified()).isFalse();
        assertThat(decision.reason()).contains("无法在本次证据链中");
    }

    @Test
    void failsClosedWhenEvidenceIsMissing() {
        NotificationPolicyEvaluator evaluator = evaluator(mock(ChatModel.class));

        NotificationPolicyEvaluator.Decision decision = evaluator.evaluate(
            "出现重大风险时发送", "报告内容", List.of());

        assertThat(decision.send()).isFalse();
        assertThat(decision.reason()).contains("没有可验证的证据");
    }

    @SuppressWarnings("unchecked")
    private NotificationPolicyEvaluator evaluator(ChatModel model) {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return new NotificationPolicyEvaluator(provider, new ObjectMapper());
    }
}
