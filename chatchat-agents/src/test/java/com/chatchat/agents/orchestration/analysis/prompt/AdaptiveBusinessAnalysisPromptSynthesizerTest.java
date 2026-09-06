package com.chatchat.agents.orchestration.analysis.prompt;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AdaptiveBusinessAnalysisPromptSynthesizerTest {
    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime("t", "u", "r", "q", "c");

    @Test void synthesizesOnceFromQuestionAndMetadataWithoutRawRows() {
        var seen = new AtomicReference<String>();
        ChatModel model = new ChatModel() {
            @Override public String chat(String prompt) {
                seen.set(prompt);
                return response();
            }
        };
        var dataset = new Dataset("trades", Map.of(
            "source", Map.of("displayName", "客户交易", "description", "客户成交明细"),
            "schema", Map.of("fields", List.of(Map.of("name", "amount", "label", "成交金额", "type", "decimal")))),
            List.of(Map.of("amount", 100, "secretRowValue", "must-not-enter-planning")));
        var metadata = new LinkedHashMap<String, Object>();

        var result = new AdaptiveBusinessAnalysisPromptSynthesizer().synthesize(
            "识别活跃度下降客户", List.of(dataset), model, scope,
            AnalysisEvidenceSpillStore.disabled(), metadata, () -> { });

        assertThat(result.mode()).isEqualTo("MODEL_SYNTHESIZED");
        assertThat(result.compiledPrompt()).contains("客户经营分析师", "CONTRIBUTION");
        assertThat(seen.get()).contains("识别活跃度下降客户", "客户交易", "成交金额")
            .doesNotContain("must-not-enter-planning");
        assertThat(metadata).containsEntry("adaptiveAnalysisPromptModelCalls", 1)
            .containsEntry("adaptiveAnalysisPromptMode", "MODEL_SYNTHESIZED");
    }

    @Test void malformedSynthesisFallsBackWithoutBlockingAnalysis() {
        var metadata = new LinkedHashMap<String, Object>();
        ChatModel malformed = new ChatModel() {
            @Override public String chat(String prompt) { return "not-json"; }
        };
        var result = new AdaptiveBusinessAnalysisPromptSynthesizer().synthesize("分析",
            List.of(new Dataset("d", Map.of("source", Map.of("displayName", "数据")), List.of())),
            malformed, scope, AnalysisEvidenceSpillStore.disabled(), metadata, () -> { });
        assertThat(result.mode()).isEqualTo("SAFE_FALLBACK");
        assertThat(result.compiledPrompt()).contains("业务数据分析师");
        assertThat(metadata).containsEntry("adaptiveAnalysisPromptFallbackReason", "IllegalArgumentException");
    }

    @Test void reusesPromptContractByQuestionContextAndModelFingerprint() {
        Map<String, String> cache = new HashMap<>();
        var store = mock(AnalysisEvidenceSpillStore.class);
        when(store.readCheckpoint(any(), anyString(), anyString())).thenAnswer(call ->
            Optional.ofNullable(cache.get(call.getArgument(1) + ":" + call.getArgument(2))));
        doAnswer(call -> {
            cache.put(call.getArgument(1) + ":" + call.getArgument(2), call.getArgument(3));
            return null;
        }).when(store).checkpoint(any(), anyString(), anyString(), anyString());
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public String chat(String prompt) { calls.incrementAndGet(); return response(); }
        };
        var datasets = List.of(new Dataset("d", Map.of("source", Map.of("displayName", "客户交易")), List.of()));
        var synthesizer = new AdaptiveBusinessAnalysisPromptSynthesizer();
        synthesizer.synthesize("分析活跃度", datasets, model, scope, store, new LinkedHashMap<>(), () -> { });
        var metadata = new LinkedHashMap<String, Object>();
        var restored = synthesizer.synthesize("分析活跃度", datasets, model, scope, store, metadata, () -> { });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(restored.mode()).isEqualTo("CHECKPOINT_RESTORED");
        assertThat(metadata).containsEntry("adaptiveAnalysisPromptModelCalls", 0);
    }

    public static String response() {
        return "{\"schemaVersion\":\"dynamic_analysis_prompt.v1\","
            + "\"role\":{\"name\":\"客户经营分析师\",\"perspective\":\"客户活跃度\",\"responsibilities\":[\"识别变化\"]},"
            + "\"objective\":{\"goal\":\"识别活跃度下降\",\"decision\":\"确定跟进客户\"},"
            + "\"methodology\":[\"COMPARE\",\"CONTRIBUTION\"],\"focus\":[\"交易金额\"],"
            + "\"constraints\":[\"不推断客户流失\"],\"evidenceRequirements\":[\"引用证据\"],"
            + "\"output\":[\"EXECUTIVE_SUMMARY\",\"KEY_FINDINGS\",\"RECOMMENDED_ACTIONS\"]}";
    }
}
