package com.chatchat.chat.asset;

import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetGuidanceInteractionBridgeTest {
    @Test void explanationTextDoesNotTurnMissingEvidenceIntoSuccess() {
        var runtime = mock(AnalysisRuntimePort.class);
        var outcome = new com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome(null,
            com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType.ASSET_GUIDANCE, null, null, null,
            "没有足够元数据", Map.of("runtimePublicStatus", "NO_PRESENTABLE_RESULT"));
        when(runtime.analyze(any())).thenReturn(outcome);
        var agent = mock(SkillDefinition.class);
        when(agent.id()).thenReturn("asset");
        var response = new AssetGuidanceInteractionBridge(runtime).execute(
            InteractionRequest.builder().query("API 怎么用").tenantId("t").userId("u").build(),
            InteractionContext.builder().requestId("r").conversationId("c").build(), agent);
        assertThat(response.getMetadata().get("agent")).isEqualTo(Map.of("publicStatus", "NO_PRESENTABLE_RESULT"));
        assertThat(response.getAnswer()).isEqualTo("没有足够元数据");
    }
}
