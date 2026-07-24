package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallFingerprintTest {

    @Test
    void treatsWordingOnlySearchChangesAsEquivalent() {
        InterpretationPlan first = plan("查询系统部署架构");
        InterpretationPlan second = plan("查找系统的部署架构相关内容");

        assertThat(ToolCallFingerprint.materiallyEquivalent(first, second)).isTrue();
    }

    @Test
    void recognizesAnAddedSearchConstraintAsMaterialChange() {
        InterpretationPlan first = plan("查询系统部署架构");
        InterpretationPlan second = plan("查询系统部署架构 端口配置");

        assertThat(ToolCallFingerprint.materiallyEquivalent(first, second)).isFalse();
    }

    private InterpretationPlan plan(String query) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("analysis", "test", "low"),
            null,
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "mcp_tool",
                    "document_search",
                    Map.of("query", query, "limit", 10),
                    List.of(),
                    null,
                    null
                ),
                new InterpretationPlan.Step(
                    2,
                    "final_answer",
                    null,
                    Map.of(),
                    List.of(1),
                    null,
                    null
                )
            )),
            null,
            null
        );
    }
}
