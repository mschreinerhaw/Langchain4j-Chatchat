package com.chatchat.chat.asset;

import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetGuidanceInteractionBridgeTest {
    @Test void guidanceRoutesBeforeDataAnalysisAndHonorsExplicitChoice() {
        var bridge = new AssetGuidanceInteractionBridge(mock(AnalysisRuntimePort.class));
        var agent = mock(SkillDefinition.class);
        when(agent.defaultMode()).thenReturn("agent_chat");
        var request = InteractionRequest.builder().query("这个API是做什么用").build();
        assertThat(bridge.matches(request, agent)).isTrue();
        request.setToolInput(Map.of("workflowFamily", "DATA_ANALYSIS"));
        assertThat(bridge.matches(request, agent)).isFalse();
        request.setQuery("position_template");
        request.setToolInput(Map.of("workflowFamily", "ASSET_GUIDANCE"));
        assertThat(bridge.matches(request, agent)).isTrue();
        when(agent.defaultMode()).thenReturn("role_chat");
        assertThat(bridge.matches(request, agent)).isFalse();
    }
}
