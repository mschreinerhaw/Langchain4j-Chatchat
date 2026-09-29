package com.chatchat.chat.asset;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.asset.AssetContext;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.skills.DomainSkillRuntimePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;

class PublishedSkillGuidanceEnhancerTest {
    final DomainSkillRuntimePort skills = mock(DomainSkillRuntimePort.class);
    final SkillCatalogService agents = mock(SkillCatalogService.class);
    final ChatModel model = mock(ChatModel.class);
    final ConfigurableChatModelFactory models = mock(ConfigurableChatModelFactory.class);
    final AnalysisContext context = new AnalysisContext("持仓接口怎么用", new KernelDataScope("t", "u", "r", null, null, null, Map.of()),
        "agent", List.of(), List.of(), List.of("role"), null, Map.of());
    final AssetContext asset = new AssetContext("positions", "API", "持仓", "持仓信息", Map.of(), Map.of(), Map.of(), "discovery", Map.of());
    PublishedSkillGuidanceEnhancer enhancer() {
        var agent = mock(SkillDefinition.class);
        when(agents.resolve("agent")).thenReturn(agent);
        when(agent.id()).thenReturn("agent");
        when(agent.workflowConfig()).thenReturn(Map.of("boundDomainSkillIds", List.of("skill")));
        return new PublishedSkillGuidanceEnhancer(skills, agents, model, models, new ObjectMapper());
    }
    @Test void authorizedSkillEnhancesMetadataWithoutTools() {
        var enhancer = enhancer();
        try {
            when(skills.retrievePublishedForAgent(eq("t"), eq("u"), eq(List.of("role")), anyString(), eq(List.of("skill")), eq("agent")))
                .thenReturn(List.of(new DomainSkillRuntimePort.DomainSkillContent("skill", "持仓分析", "金融", "需要持仓比例与行业分类")));
            when(model.chat(anyString())).thenReturn("建议用于集中度分析，需先确认输出字段。");
            var result = enhancer.recommend(context, asset);
            assertThat(result.status()).isEqualTo("APPLIED");
            assertThat(result.skillIds()).containsExactly("skill");
            verify(model).chat(argThat((String prompt) -> prompt.contains("Never claim template execution")
                && prompt.contains("concise Chinese advice") && prompt.contains("需要持仓比例与行业分类")));
        } finally { enhancer.close(); }
    }
    @Test void noAuthorizedSkillsMeansNoModelRequest() {
        var enhancer = enhancer();
        try {
            when(skills.retrievePublishedForAgent(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
            assertThat(enhancer.recommend(context, asset).status()).isEqualTo("NO_MATCH");
            verifyNoInteractions(model, models);
        } finally { enhancer.close(); }
    }
    @Test void modelFailureProducesPartialGuidanceInsteadOfLosingMetadata() {
        var enhancer = enhancer();
        try {
            when(skills.retrievePublishedForAgent(any(), any(), any(), any(), any(), any())).thenReturn(
                List.of(new DomainSkillRuntimePort.DomainSkillContent("skill", "技能", "金融", "说明")));
            when(model.chat(anyString())).thenThrow(new IllegalStateException("model failed"));
            assertThat(enhancer.recommend(context, asset).status()).isEqualTo("UNAVAILABLE");
        } finally { enhancer.close(); }
    }
}
