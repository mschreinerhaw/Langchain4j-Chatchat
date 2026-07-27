package com.chatchat.agents.assessment;

import com.chatchat.agents.evidence.AnswerAssemblyMode;
import com.chatchat.agents.evidence.AnswerAssemblyPolicy;
import com.chatchat.common.interaction.InteractionToolTrace;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskResultAssessmentCompilerTest {

    private final TaskResultAssessmentCompiler compiler = new TaskResultAssessmentCompiler();

    @Test
    void partialEvidenceProducesPartialArtifactWithoutInventingCoverage() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("interpretationPlanEvidenceHistory", List.of(Map.of(
            "sufficient", false,
            "evidenceUsed", List.of("客户姓名", "客户词根"),
            "missingEvidence", List.of("证件字段", "联系方式字段"),
            "confidence", 0.72D
        )));
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("enterprise_metadata_search")
            .success(true)
            .build();

        TaskResultAssessment result = compiler.compile(metadata, List.of(trace), null);

        assertThat(result.execution().status()).isEqualTo(TaskResultAssessment.ExecutionStatus.SUCCESS);
        assertThat(result.evidence().status()).isEqualTo(TaskResultAssessment.EvidenceStatus.PARTIAL);
        assertThat(result.evidence().coverage()).isNull();
        assertThat(result.evidence().quality()).isEqualTo(0.72D);
        assertThat(result.fulfillment().status()).isEqualTo(TaskResultAssessment.FulfillmentStatus.PARTIAL);
        assertThat(result.delivery().decision()).isEqualTo(TaskResultAssessment.DeliveryDecision.PARTIAL_ARTIFACT);
        assertThat(result.delivery().claimPolicy())
            .isEqualTo(TaskResultAssessment.ClaimPolicy.SUPPORTED_FACTS_PLUS_LABELED_PROPOSALS);
        assertThat(compiler.promptInstructions(result))
            .contains("deliver a useful partial artifact")
            .contains("设计建议（非企业标准证据）")
            .contains("无法生成");
    }

    @Test
    void strictEvidencePolicyKeepsPartialResultFactsOnly() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("interpretationPlanEvidenceHistory", List.of(Map.of(
            "sufficient", false,
            "evidenceUsed", List.of("已核验字段"),
            "missingEvidence", List.of("未核验字段")
        )));
        metadata.put(TaskResultAssessmentCompiler.POLICY_KEY, Map.of(
            "strictEvidenceOnly", true
        ));

        TaskResultAssessment result = compiler.compile(metadata, List.of(), null);

        assertThat(result.delivery().decision()).isEqualTo(TaskResultAssessment.DeliveryDecision.FACTS_ONLY);
        assertThat(result.delivery().claimPolicy())
            .isEqualTo(TaskResultAssessment.ClaimPolicy.SUPPORTED_FACTS_ONLY);
    }

    @Test
    void evidenceAssemblyRefusalCannotBeBypassedBySuccessfulTool() {
        AnswerAssemblyPolicy refusal = new AnswerAssemblyPolicy(
            null,
            AnswerAssemblyMode.REFUSE,
            false,
            null,
            null,
            "A",
            1,
            List.of(),
            List.of("缺少可信证据")
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("search")
            .success(true)
            .build();

        TaskResultAssessment result = compiler.compile(Map.of(), List.of(trace), refusal);

        assertThat(result.evidence().status()).isEqualTo(TaskResultAssessment.EvidenceStatus.INSUFFICIENT);
        assertThat(result.delivery().decision()).isEqualTo(TaskResultAssessment.DeliveryDecision.REFUSE);
        assertThat(result.delivery().allowed()).isFalse();
    }

    @Test
    void nonEmptyMcpResultAlwaysAllowsPartialAnalysisEvenWhenAssemblyWouldRefuse() {
        AnswerAssemblyPolicy refusal = new AnswerAssemblyPolicy(
            null,
            AnswerAssemblyMode.REFUSE,
            false,
            null,
            null,
            "A",
            1,
            List.of(),
            List.of("missing additional metrics")
        );
        InteractionToolTrace trace = InteractionToolTrace.builder()
            .toolName("oracle_tablespace_query")
            .success(true)
            .output("""
                {"success":true,"rows":[{"tablespace":"USERS","size_mb":102400}],"rowCount":1}
                """)
            .build();

        TaskResultAssessment result = compiler.compile(Map.of(), List.of(trace), refusal);

        assertThat(result.evidence().status())
            .isEqualTo(TaskResultAssessment.EvidenceStatus.PARTIAL);
        assertThat(result.evidence().availability())
            .isEqualTo(TaskResultAssessment.EvidenceAvailability.AVAILABLE);
        assertThat(result.evidence().analysisCapability())
            .isEqualTo(TaskResultAssessment.AnalysisCapability.PARTIAL);
        assertThat(result.evidence().answerAllowed()).isTrue();
        assertThat(result.evidence().blockingReason()).isNull();
        assertThat(result.delivery().decision())
            .isEqualTo(TaskResultAssessment.DeliveryDecision.PARTIAL_ARTIFACT);
        assertThat(result.delivery().allowed()).isTrue();
    }

    @Test
    void executionFailureRemainsSeparateFromEvidenceAssessment() {
        InteractionToolTrace success = InteractionToolTrace.builder().toolName("a").success(true).build();
        InteractionToolTrace failure = InteractionToolTrace.builder().toolName("b").success(false).build();

        TaskResultAssessment result = compiler.compile(Map.of(), List.of(success, failure), null);

        assertThat(result.execution().status())
            .isEqualTo(TaskResultAssessment.ExecutionStatus.PARTIAL_SUCCESS);
        assertThat(result.execution().successfulTools()).isEqualTo(1);
        assertThat(result.execution().failedTools()).isEqualTo(1);
        assertThat(result.evidence().status()).isEqualTo(TaskResultAssessment.EvidenceStatus.PARTIAL);
    }

    @Test
    void ordinaryNoToolAnswerIsNotConvertedIntoAnExecutionFailure() {
        TaskResultAssessment result = compiler.compile(Map.of(), List.of(), null);

        assertThat(result.execution().status()).isEqualTo(TaskResultAssessment.ExecutionStatus.SUCCESS);
        assertThat(result.evidence().status()).isEqualTo(TaskResultAssessment.EvidenceStatus.NONE);
        assertThat(result.fulfillment().status()).isEqualTo(TaskResultAssessment.FulfillmentStatus.COMPLETE);
        assertThat(result.delivery().decision()).isEqualTo(TaskResultAssessment.DeliveryDecision.FULL_ARTIFACT);
    }

    @Test
    void taskContractControlsWhetherMissingEvidenceBlocksCompletion() {
        Map<String, Object> optional = Map.of(
            "taskContract",
            new TaskContract(
                null, "design", "设计方案",
                TaskContract.EvidenceRequirement.OPTIONAL,
                true, "advice", List.of())
        );
        Map<String, Object> required = Map.of(
            "taskContract",
            new TaskContract(
                null, "query", "查询运行状态",
                TaskContract.EvidenceRequirement.REQUIRED,
                false, "facts", List.of("status_query"))
        );

        TaskResultAssessment optionalResult = compiler.compile(optional, List.of(), null);
        TaskResultAssessment requiredResult = compiler.compile(required, List.of(), null);

        assertThat(optionalResult.fulfillment().status())
            .isEqualTo(TaskResultAssessment.FulfillmentStatus.COMPLETE);
        assertThat(requiredResult.evidence().status())
            .isEqualTo(TaskResultAssessment.EvidenceStatus.INSUFFICIENT);
        assertThat(requiredResult.fulfillment().status())
            .isEqualTo(TaskResultAssessment.FulfillmentStatus.UNFULFILLED);
        assertThat(requiredResult.delivery().decision())
            .isEqualTo(TaskResultAssessment.DeliveryDecision.RETRY);
    }
}
