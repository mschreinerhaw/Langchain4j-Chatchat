package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.evidence.normalization.EvidenceChunk;

import com.chatchat.agents.orchestration.AgentOrchestrator;

import com.chatchat.agents.orchestration.answer.AgentAnswerFinalizer;
import com.chatchat.agents.protocol.AnswerContract;
import com.chatchat.agents.orchestration.answer.AnswerContractCompiler;
import com.chatchat.agents.orchestration.answer.AnswerCriticRepairer;
import com.chatchat.agents.orchestration.evidence.EvidenceSufficiencyGate;
import com.chatchat.agents.orchestration.planning.validation.AgentRuntimeGuard;

import com.chatchat.agents.runtime.config.AgentRuntimeProperties;

import com.chatchat.agents.runtime.answer.AgentAnswerReview;
import com.chatchat.agents.runtime.answer.AgentAnswerReviewer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnswerQualityPipelineTest {

    @Test
    void compilesTheSameContractShapeAcrossUnrelatedBusinessScenarios() {
        AnswerContractCompiler compiler = new AnswerContractCompiler();
        List<String> requests = List.of(
            "比较两组经营指标；说明差异与风险",
            "总结所附制度文件；列出关键依据",
            "分析服务异常；给出排查步骤"
        );

        List<AnswerContract> contracts = requests.stream()
            .map(request -> compiler.compile(request, "结论优先", Map.of()))
            .toList();

        assertThat(contracts).allSatisfy(contract -> {
            assertThat(contract.contractVersion()).isEqualTo(AnswerContract.VERSION);
            assertThat(contract.outputFormat()).isEqualTo("MARKDOWN");
            assertThat(contract.evidencePolicy()).isEqualTo(AnswerContract.EVIDENCE_OPTIONAL);
            assertThat(contract.constraints()).containsExactly("结论优先");
            assertThat(contract.deliverables()).hasSize(2);
        });
        assertThat(contracts).extracting(AnswerContract::goal).containsExactlyElementsOf(requests);
    }

    @Test
    void explicitRuntimeMetadataOverridesDefaultsWithoutDomainBranches() {
        AnswerContract contract = new AnswerContractCompiler().compile(
            "生成交付结果",
            null,
            Map.of(
                "requiredDeliverables", List.of("结论", "依据", "限制"),
                "answerOutputFormat", "json",
                "responseLanguage", "zh-CN",
                "evidenceRequired", true,
                "responseSchema", "result.v2"
            )
        );

        assertThat(contract.deliverables()).containsExactly("结论", "依据", "限制");
        assertThat(contract.outputFormat()).isEqualTo("JSON");
        assertThat(contract.language()).isEqualTo("ZH-CN");
        assertThat(contract.evidencePolicy()).isEqualTo(AnswerContract.EVIDENCE_REQUIRED);
        assertThat(contract.constraints()).contains("responseSchema=result.v2");
    }

    @Test
    void evidenceGateBlocksStrongClaimsWhenRequiredEvidenceIsUnavailable() {
        AnswerContract required = new AnswerContractCompiler().compile(
            "回答问题", null, Map.of("evidenceRequired", true));
        EvidenceSufficiencyGate gate = new EvidenceSufficiencyGate();

        EvidenceSufficiencyGate.Decision missing = gate.evaluate(required, List.of());
        EvidenceSufficiencyGate.Decision partial = gate.evaluate(required, List.of(
            "evidence_v1 EvidenceChunk ref=doc://a#chunk=1",
            "tool observation reports failure"
        ));

        assertThat(missing.status()).isEqualTo(EvidenceSufficiencyGate.INSUFFICIENT);
        assertThat(missing.strongClaimsAllowed()).isFalse();
        assertThat(missing.retrieveMoreRecommended()).isTrue();
        assertThat(partial.status()).isEqualTo(EvidenceSufficiencyGate.PARTIAL);
        assertThat(partial.strongClaimsAllowed()).isTrue();
    }

    @Test
    void criticReturnsLocalizedDefectsAndATargetedRepair() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {"pass":false,"issues":[{"code":"missing_deliverable","location":"结尾","instruction":"补充限制"}],
             "repairedAnswer":"## 结论\\n\\n已完成。\\n\\n## 限制\\n\\n当前证据仅支持上述范围。"}
            """);
        AnswerContract contract = new AnswerContractCompiler().compile(
            "给出结论；说明限制", null, Map.of());
        EvidenceSufficiencyGate.Decision gate = new EvidenceSufficiencyGate().evaluate(contract, List.of());

        AnswerCriticRepairer.Result result = new AnswerCriticRepairer(new ObjectMapper()).review(
            model, contract, gate, "## 结论\n\n已完成。", List.of());

        assertThat(result.available()).isTrue();
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).extracting(AnswerCriticRepairer.Issue::code)
            .containsExactly("missing_deliverable");
        assertThat(result.repairedAnswer()).contains("## 限制");
    }

    @Test
    void finalizerAppliesSafeRepairAndPublishesAllQualityContracts() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {"pass":false,"issues":[{"code":"missing_limit","location":"answer","instruction":"add limitation"}],
             "repairedAnswer":"## 结论\\n\\n可交付。\\n\\n## 限制\\n\\n没有额外证据，结论仅限当前输入。"}
            """);
        AgentAnswerReviewer reviewer = (chatModel, query, systemPrompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok");
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setFinalSummaryWebSearchEnabled(false);
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            reviewer,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt"),
            null, null, null, new ObjectMapper(), properties
        );
        Map<String, Object> metadata = new LinkedHashMap<>();

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
            model,
            "给出结论；说明限制",
            "使用简洁 Markdown",
            List.of(),
            metadata,
            List.of(),
            "## 结论\n\n可交付。",
            () -> false,
            "final_answer"
        );

        assertThat(result.answer()).contains("## 限制");
        assertThat(result.metadata())
            .containsKeys("answerContract", "evidenceSufficiencyGate", "answerCritic")
            .containsEntry("answerTargetedRepairApplied", true)
            .containsEntry("businessHardcodingPolicy", "runtime_contract_only");
    }

    @Test
    void compilerAndGateRemainStatelessUnderParallelScenarioPressure() {
        AnswerContractCompiler compiler = new AnswerContractCompiler();
        EvidenceSufficiencyGate gate = new EvidenceSufficiencyGate();
        Map<Integer, String> results = new ConcurrentHashMap<>();

        IntStream.range(0, 1_000).parallel().forEach(index -> {
            String request = "场景-" + index + "；输出项-" + index;
            AnswerContract contract = compiler.compile(request, null,
                Map.of("evidenceRequired", index % 2 == 0));
            List<String> observations = index % 3 == 0
                ? List.of("evidence_v1 EvidenceChunk ref=doc://" + index + "#chunk=1")
                : new ArrayList<>();
            EvidenceSufficiencyGate.Decision decision = gate.evaluate(contract, observations);
            results.put(index, contract.goal() + "|" + decision.status());
        });

        assertThat(results).hasSize(1_000);
        IntStream.range(0, 1_000).forEach(index ->
            assertThat(results.get(index)).startsWith("场景-" + index + "；输出项-" + index + "|"));
    }
}
