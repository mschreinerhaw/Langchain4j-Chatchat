package com.chatchat.agents.orchestration.analysis.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicAnalysisPromptContractTest {
    @Test void validatesAndCompilesGuidanceWithoutExecutionAuthority() {
        var contract = DynamicAnalysisPromptContract.from(Map.of(
            "schemaVersion", DynamicAnalysisPromptContract.SCHEMA_VERSION,
            "role", Map.of("name", "客户经营分析师", "perspective", "客户活跃度"),
            "objective", Map.of("goal", "识别活跃度下降", "decision", "确定跟进客户"),
            "methodology", List.of("COMPARE", "CONTRIBUTION"),
            "focus", List.of("交易金额", "交易次数"),
            "constraints", List.of("不得把单月波动解释为流失"),
            "evidenceRequirements", List.of("结论引用证据"),
            "output", List.of("EXECUTIVE_SUMMARY", "KEY_FINDINGS")));

        assertThat(contract.toMap()).containsEntry("authority", "ANALYSIS_GUIDANCE_ONLY")
            .containsEntry("executionBoundary", "MODEL_DECIDES_HOW_TO_ANALYZE_RUNTIME_DECIDES_WHAT_IS_LEGAL_TO_EXECUTE");
        assertThat(contract.compile()).contains("客户经营分析师", "COMPARE", "grants no execution authority");
    }

    @Test void rejectsInventedMethodAndProvidesUsableFallback() {
        assertThatThrownBy(() -> DynamicAnalysisPromptContract.from(Map.of(
            "schemaVersion", DynamicAnalysisPromptContract.SCHEMA_VERSION,
            "role", Map.of("name", "analyst"), "objective", Map.of("goal", "goal"),
            "methodology", List.of("RUN_SQL"))))
            .hasMessageContaining("Unsupported dynamic prompt enum");
        assertThat(DynamicAnalysisPromptContract.fallback("分析当前数据", Map.of()).compile())
            .contains("分析当前数据", "结论明确限定在观察期间与样本范围", "ordered H2 guidance");
    }
}
