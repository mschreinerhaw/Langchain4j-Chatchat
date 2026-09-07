package com.chatchat.agents.orchestration.analysis.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisOutputAdmissionPolicyTest {

    @Test
    void removesStandaloneInstructionsWithoutChangingReportFacts() {
        String body = "# 分析报告\r\n\r\n当前样本数值为 42。";
        String cleaned = AnalysisOutputAdmissionPolicy.sanitizeNarrative(
            "以下工具结果是本次分析的事实基础。\r\n" + body);
        assertThat(cleaned).isEqualTo(body);
        assertThat(AnalysisOutputAdmissionPolicy.admit(cleaned).admitted()).isTrue();
        assertThat(AnalysisOutputAdmissionPolicy.sanitizeNarrative(body)).isEqualTo(body);
    }

    @Test
    void cleanupCannotPromoteInstructionOnlyOutputOrRewriteQuotedEvidence() {
        assertThat(AnalysisOutputAdmissionPolicy.admit(AnalysisOutputAdmissionPolicy.sanitizeNarrative(
            "以下工具结果是本次分析的事实基础。")).admitted()).isFalse();
        for (String body : java.util.List.of(
            "记录原文：以下工具结果是本次分析的事实基础，数值为42。",
            "````text\n```\n以下工具结果是本次分析的事实基础。\n````",
            "{\"schemaVersion\":\"governed_management_synthesis.v4\",\"findings\":[]}")) {
            assertThat(AnalysisOutputAdmissionPolicy.sanitizeNarrative(body)).isEqualTo(body);
            assertThat(AnalysisOutputAdmissionPolicy.admit(body).admitted()).isFalse();
        }
    }

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
