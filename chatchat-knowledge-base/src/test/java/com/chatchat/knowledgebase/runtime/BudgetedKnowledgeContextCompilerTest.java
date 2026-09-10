package com.chatchat.knowledgebase.runtime;

import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeRequest;
import com.chatchat.common.knowledge.KnowledgeScope;
import com.chatchat.common.knowledge.KnowledgeSkillPlan;
import com.chatchat.common.knowledge.KnowledgeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetedKnowledgeContextCompilerTest {

    private final BudgetedKnowledgeContextCompiler compiler = new BudgetedKnowledgeContextCompiler();

    @Test
    void deduplicatesAndHardLimitsCompiledKnowledge() {
        KnowledgeRequest request = new KnowledgeRequest(
            "v", "分析客户集中度风险", "RISK", 40,
            new KnowledgeScope("risk-agent", "tenant", "user", List.of("doc-1"), List.of(), List.of()),
            null, Map.of());
        KnowledgeSkillPlan plan = new DefaultKnowledgeSkillSynthesizer().synthesize(request);
        String content = "集中度风险需要结合单票占比、Top3占比和行业集中度判断，不得使用示例数据作为客户事实。";
        KnowledgeIR first = unit("one", content, 0.9);
        KnowledgeIR duplicate = unit("two", content, 0.8);

        var result = compiler.compile(request, plan, List.of(first, duplicate));

        assertThat(result.estimatedTokens()).isLessThanOrEqualTo(40);
        assertThat(result.truncated()).isTrue();
        assertThat(result.knowledgeUnits()).hasSize(1);
        assertThat(result.compiledContext()).contains("[RULE]");
    }

    @Test
    void estimatesChineseByCodePointInsteadOfUtf8Bytes() {
        assertThat(compiler.conservativeTokenEstimate("股票期权证券盈亏")).isEqualTo(8);
        assertThat(compiler.conservativeTokenEstimate("abcdefgh")).isEqualTo(2);
    }

    @Test
    void prioritizesRulesOverExamplesWhenBudgetIsTight() {
        KnowledgeRequest request = new KnowledgeRequest(
            "v", "判断适用场景", "GUIDANCE", 18,
            new KnowledgeScope("advisor", "tenant", "user", List.of("doc-1"), List.of(), List.of()),
            null, Map.of());
        KnowledgeSkillPlan plan = new DefaultKnowledgeSkillSynthesizer().synthesize(request);
        KnowledgeIR example = new KnowledgeIR("example", "product", KnowledgeType.EXAMPLE, "示例",
            "这是历史示例，不能作为当前事实。", List.of(), List.of(), List.of(), List.of(),
            "这是历史示例，不能作为当前事实。", null, 0.95D);
        KnowledgeIR rule = new KnowledgeIR("rule", "product", KnowledgeType.RULE, "规则",
            "优先说明适用边界和数据时点。", List.of(), List.of(), List.of(), List.of(),
            "优先说明适用边界和数据时点。", null, 0.60D);

        var result = compiler.compile(request, plan, List.of(example, rule));

        assertThat(result.knowledgeUnits()).isNotEmpty();
        assertThat(result.knowledgeUnits().get(0).knowledgeId()).isEqualTo("rule");
    }

    private KnowledgeIR unit(String id, String content, double relevance) {
        return new KnowledgeIR(id, "securities.risk", KnowledgeType.RULE, "集中度规则", content,
            List.of(), List.of(), List.of("RISK"), List.of(), content, null, relevance);
    }
}
