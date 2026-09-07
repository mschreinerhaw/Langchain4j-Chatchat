package com.chatchat.agents.orchestration.analysis.prompt;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class DomainAnalysisProfilePlanningTest {
    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime("tenant-a", "u", "r", "q", "c");
    private DomainAnalysisProfileProvider.Profile profile(String title, long revision) {
        return new DomainAnalysisProfileProvider.Profile("RETAIL", "零售分析", "零售经营分析", revision,
            Map.of("focus", List.of("数据库维护的零售视角"), "output", List.of("KEY_FINDINGS"),
                "sectionTitles", Map.of("KEY_FINDINGS", title)));
    }
    @Test void loadsOnlyThePlannedProfileAndSupportsNewTypesWithoutCodeChanges() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(AdaptiveBusinessAnalysisPromptSynthesizerTest.response()
            .replace("\"schemaVersion\":", "\"analysisType\":\"RETAIL\",\"schemaVersion\":"));
        var synthesizer = new AdaptiveBusinessAnalysisPromptSynthesizer(tenant -> {
            assertThat(tenant).isEqualTo("tenant-a"); return List.of(profile("门店表现", 3));
        });
        var result = synthesizer.synthesize("分析门店", List.of(), model, scope,
            AnalysisEvidenceSpillStore.disabled(), new LinkedHashMap<>(), () -> {});
        assertThat(result.contract().toMap()).containsEntry("analysisType", "RETAIL").containsEntry("domainProfileRevision", 3L);
        assertThat(result.compiledPrompt()).contains("门店表现", "数据库维护的零售视角").doesNotContain("持仓", "成交结构");
    }
    @Test void declaredGenericAndMissingProfilesNeverLoadBusinessScaffolds() {
        for (String type : List.of("GENERIC", "NOT_CONFIGURED")) {
            Map<String, Object> metadata = new LinkedHashMap<>(Map.of("analysisType", type));
            var result = new AdaptiveBusinessAnalysisPromptSynthesizer(tenant -> List.of(profile("门店表现", 1)))
                .synthesize("交易系统资产性能", List.of(), null, scope, AnalysisEvidenceSpillStore.disabled(), metadata, () -> {});
            assertThat(result.contract().toMap()).containsEntry("analysisType", "GENERIC").doesNotContainKey("domainFocus");
            assertThat(result.compiledPrompt()).doesNotContain("门店表现", "持仓明细");
        }
    }
    @Test void explicitTypeFallbackUsesCurrentDatabaseValuesAndProviderFailureIsGeneric() {
        var snapshot = new AtomicReference<>(profile("第一版标题", 1));
        var synthesizer = new AdaptiveBusinessAnalysisPromptSynthesizer(tenant -> List.of(snapshot.get()));
        for (int revision = 1; revision <= 2; revision++) {
            if (revision == 2) snapshot.set(profile("第二版标题", 2));
            var result = synthesizer.synthesize("分析", List.of(), null, scope, AnalysisEvidenceSpillStore.disabled(),
                new LinkedHashMap<>(Map.of("analysisType", "RETAIL")), () -> {});
            assertThat(result.compiledPrompt()).contains(revision == 1 ? "第一版标题" : "第二版标题");
        }
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of("analysisType", "RETAIL"));
        var result = new AdaptiveBusinessAnalysisPromptSynthesizer(tenant -> { throw new IllegalStateException(); })
            .synthesize("分析", List.of(), null, scope, AnalysisEvidenceSpillStore.disabled(), metadata, () -> {});
        assertThat(result.contract().toMap()).containsEntry("analysisType", "GENERIC");
        assertThat(metadata).containsEntry("domainProfileLoadStatus", "UNAVAILABLE_GENERIC_FALLBACK");
    }

    @Test void profileContentChangesInvalidateThePlanningCheckpoint() {
        var snapshot = new AtomicReference<>(profile("旧标题", 1));
        var provider = new AdaptiveBusinessAnalysisPromptSynthesizer(tenant -> List.of(snapshot.get()));
        var store = mock(AnalysisEvidenceSpillStore.class);
        Map<String, String> checkpoints = new HashMap<>();
        when(store.readCheckpoint(any(), anyString(), anyString())).thenAnswer(call ->
            Optional.ofNullable(checkpoints.get(call.getArgument(1) + ":" + call.getArgument(2))));
        doAnswer(call -> { checkpoints.put(call.getArgument(1) + ":" + call.getArgument(2), call.getArgument(3)); return null; })
            .when(store).checkpoint(any(), anyString(), anyString(), anyString());
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(AdaptiveBusinessAnalysisPromptSynthesizerTest.response()
            .replace("\"schemaVersion\":", "\"analysisType\":\"RETAIL\",\"schemaVersion\":"));
        provider.synthesize("分析门店", List.of(), model, scope, store, new LinkedHashMap<>(), () -> {});
        var cached = provider.synthesize("分析门店", List.of(), model, scope, store, new LinkedHashMap<>(), () -> {});
        assertThat(cached.mode()).isEqualTo("CHECKPOINT_RESTORED");
        // Direct database edits may change content without incrementing the application revision.
        snapshot.set(profile("新标题", 1));
        var fresh = provider.synthesize("分析门店", List.of(), model, scope, store, new LinkedHashMap<>(), () -> {});
        assertThat(fresh.mode()).isEqualTo("MODEL_SYNTHESIZED");
        assertThat(fresh.compiledPrompt()).contains("新标题").doesNotContain("旧标题");
        verify(model, times(2)).chat(anyString());
    }
}
