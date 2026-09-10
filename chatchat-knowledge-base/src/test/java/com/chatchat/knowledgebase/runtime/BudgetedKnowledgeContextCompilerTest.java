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

    private KnowledgeIR unit(String id, String content, double relevance) {
        return new KnowledgeIR(id, "securities.risk", KnowledgeType.RULE, "集中度规则", content,
            List.of(), List.of(), List.of("RISK"), List.of(), content, null, relevance);
    }
}
