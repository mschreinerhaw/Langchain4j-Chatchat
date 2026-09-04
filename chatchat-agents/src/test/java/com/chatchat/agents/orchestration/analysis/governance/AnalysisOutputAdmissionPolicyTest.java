package com.chatchat.agents.orchestration.analysis.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisOutputAdmissionPolicyTest {

    @Test
    void rejectsInternalMcpAnalysisInstructionAsFinalNarrative() {
        String instruction = """
            ## 基于 MCP 查询结果的分析

            MCP 工具已经成功返回非空查询结果，因此可以并且必须基于现有数据进行分析。
            以下工具结果是本次分析的事实基础。
            """;

        AnalysisOutputAdmissionPolicy.Admission admission =
            AnalysisOutputAdmissionPolicy.admit(instruction);

        assertThat(admission.admitted()).isFalse();
        assertThat(admission.reason()).isEqualTo("INTERNAL_INSTRUCTION_NOT_ANALYSIS");
    }
}
