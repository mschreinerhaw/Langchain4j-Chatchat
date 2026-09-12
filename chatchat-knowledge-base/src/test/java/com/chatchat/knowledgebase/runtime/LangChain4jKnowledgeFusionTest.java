package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeScope;
import com.chatchat.common.knowledge.KnowledgeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jKnowledgeFusionTest {

    @Test
    void reciprocalRankFusionPromotesKnowledgeConfirmedByMultipleSkillDimensions() {
        KnowledgeIR metricOnly = unit("metric-only", KnowledgeType.METRIC, "单一指标说明", 0.99);
        KnowledgeIR sharedMetric = unit("shared-metric", KnowledgeType.METRIC, "跨维度共同知识", 0.70);
        KnowledgeIR methodOnly = unit("method-only", KnowledgeType.METHOD, "单一方法说明", 0.98);
        KnowledgeIR sharedMethod = unit("shared-method", KnowledgeType.METHOD, "跨维度共同知识", 0.60);

        List<KnowledgeIR> fused = new LangChain4jKnowledgeFusion().fuse(request(),
            List.of(metricOnly, sharedMetric, methodOnly, sharedMethod));

        assertThat(fused).hasSize(3);
        assertThat(fused.get(0).compactPromptRepresentation()).isEqualTo("跨维度共同知识");
    }

    @Test
    void runtimeSkillsAreRepresentedWithLangChain4jSkillCatalog() {
        var skill = new com.chatchat.common.knowledge.KnowledgeSkillInstance(
            "metric-analysis", com.chatchat.common.knowledge.KnowledgeSkillType.METRIC_LOOKUP,
            "securities", "解释任务相关指标", List.of("资产", "盈亏"), 1, 300, Map.of());
        var plan = new com.chatchat.common.knowledge.KnowledgeSkillPlan(
            "v", "TOOL_ANALYSIS", List.of(skill), 300);

        String rendered = new LangChain4jDomainSkillAdapter().renderActivatedSkills(
            plan, List.of(unit("metric", KnowledgeType.METRIC, "指标口径", 0.8)));

        assertThat(rendered).contains("<activated_domain_skills>", "metric-analysis", "解释任务相关指标")
            .doesNotContain("<available_skills>");
    }

    private KnowledgeRequest request() {
        return new KnowledgeRequest("v", "分析资产与交易", "TOOL_ANALYSIS", 800,
            new KnowledgeScope("agent", "tenant", "user", List.of("doc"), List.of(), List.of("securities")),
            null, Map.of());
    }

    private KnowledgeIR unit(String id, KnowledgeType type, String content, double relevance) {
        return new KnowledgeIR(id, "securities", type, id, content, List.of(), List.of(),
            List.of("TOOL_ANALYSIS"), List.of(), content, null, relevance);
    }
}
