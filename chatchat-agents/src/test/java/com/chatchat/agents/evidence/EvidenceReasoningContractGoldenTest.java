package com.chatchat.agents.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceReasoningContractGoldenTest {

    private final EvidenceGraphExecutionEngine graphEngine = new EvidenceGraphExecutionEngine();
    private final EvidencePathExecutor pathExecutor = new EvidencePathExecutor();
    private final EvidenceExecutionContractCompiler compiler = new EvidenceExecutionContractCompiler();
    private final DeterministicAnswerCompiler answerCompiler = new DeterministicAnswerCompiler();
    private final EvidenceExecutionContractValidator validator = new EvidenceExecutionContractValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void goldenCaseClearExecutionStepsProducesBoundExecutionSpec() throws Exception {
        EvidenceExecutionContract contract = compile(List.of(chunk(
            "dev-flow",
            "doc://dev-flow#chunk=0",
            "select * from report_dataset where tenant_id = 1",
            0.94
        )));
        String lockedAnswer = answerCompiler.compile(contract);
        JsonNode json = lockedAnswerJson(lockedAnswer);

        assertThat(validator.validate(contract).valid()).isTrue();
        assertThat(json.get("type").asText()).isEqualTo("evidence_reasoning_v2");
        assertReasoningProtocolContract(json);
        assertThat(json.get("pathState").asText()).isEqualTo("STRONG_PATH");
        assertThat(json.at("/result/exists").asBoolean()).isTrue();
        assertThat(json.at("/result/mode").asText()).isEqualTo("CONFIRMED");
        assertThat(json.at("/result/conclusion").asText()).isNotBlank();
        assertThat(json.at("/result/evidenceSummary").asText()).contains("[Source 1]");
        assertThat(json.at("/result/answer").asText()).contains("Conclusion:");
        assertThat(json.at("/result/claims")).hasSizeGreaterThanOrEqualTo(1);
        assertThat(json.at("/result/claims/0/sourceRef").asText()).isEqualTo("doc://dev-flow#chunk=0");
        assertThat(json.at("/result/claimGraph/primaryClaimId").asText()).isEqualTo("claim-1");
        assertThat(json.at("/result/claimGraph/dominanceRule").asText()).isEqualTo("highest_confidence_bound_claim");
        assertThat(json.at("/executionSpec/steps")).hasSize(4);
        assertThat(json.at("/executionDag/nodes")).hasSize(4);
        assertThat(json.at("/executionDag/edges")).hasSize(3);
        assertThat(json.at("/reasoningTrace/type").asText()).isEqualTo("evidence_reasoning_trace_v13");
        assertThat(json.at("/reasoningTrace/pathDecision/weakestLink").asDouble()).isBetween(0.0, 1.0);
        assertThat(json.at("/reasoningTrace/pathDecision/pathCoherence").asDouble()).isBetween(0.0, 1.0);
        assertStepBindings(contract);
        assertThat(contract.evidence().direct()).extracting(EvidenceExecutionContract.EvidenceItem::refId)
            .contains("doc://dev-flow#chunk=0");
    }

    @Test
    void structureAwareClaimCompilerCreatesGenericEvidenceBundle() throws Exception {
        EvidenceExecutionContract contract = compile(List.of(chunk(
            "generic-flow",
            "doc://generic-flow#chunk=0",
            "数据处理作业说明 - 操作流程 1. 进入配置模块并维护需要处理的数据源 "
                + "2. 选择添加数据目录并维护数据连接 3. 在开发视窗中编写查询 SQL "
                + "4. 保存 SQL 数据集配置 5. 测试连接并预览数据 6. 发布处理结果。",
            0.88
        )));
        String lockedAnswer = answerCompiler.compile(contract);
        JsonNode json = lockedAnswerJson(lockedAnswer);

        assertThat(json.at("/result/exists").asBoolean()).isTrue();
        assertThat(json.at("/result/claims/0/type").asText()).isEqualTo("structured_evidence_bundle");
        assertThat(json.at("/result/claims/0/steps")).hasSizeGreaterThanOrEqualTo(5);
        assertThat(json.at("/result/structure/type").asText()).isEqualTo("evidence_structure_v1");
        assertThat(json.at("/result/conclusion").asText()).contains("数据处理作业说明");
        assertThat(json.at("/result/conclusion").asText().length()).isLessThan(180);
        assertThat(json.at("/result/answer").asText()).contains("Supporting evidence");
        assertThat(json.at("/result/answer").asText()).doesNotContain("主要流程");
        assertThat(json.at("/result/supportingEvidence")).hasSizeGreaterThanOrEqualTo(2);
        assertThat(json.at("/result/supportingEvidence/0").asText()).contains("Document content supports");
        assertThat(json.at("/result/supportingEvidence/2").asText()).contains("SQL");
        assertThat(json.at("/result/evidenceClaims")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(json.at("/result/evidenceClaims/0/claim").asText()).contains("Document content supports");
        assertThat(json.at("/result/evidenceClaims/0/support/0/sourceRef").asText()).isEqualTo("doc://generic-flow#chunk=0");
        assertThat(json.at("/result/evidenceClaims/1/support/0/text").asText()).contains("数据");
        assertThat(json.at("/result/evidenceClaims/2/support/0/text").asText()).contains("SQL");
        assertThat(json.at("/result/evidenceSummary").asText()).contains("Document content supports");
        assertThat(json.at("/result/answer").asText()).contains("SQL");
        assertThat(json.at("/result/answer").asText()).doesNotContain("1. 进入");
    }
    @Test
    void goldenCaseMultiEvidenceKeepsDirectSupportingAndStableLockedAnswer() throws Exception {
        List<EvidenceChunk> chunks = List.of(
            chunk("primary", "doc://primary#chunk=0", "核心说明：系统先完成业务数据核验并保存校验结果。", 0.92),
            chunk("supporting", "doc://supporting#chunk=1", "辅助说明：校验结果会进入报告视图，用于后续复核。", 0.86)
        );

        EvidenceExecutionContract first = compile(chunks);
        EvidenceExecutionContract second = compile(chunks);
        String firstAnswer = answerCompiler.compile(first);
        String secondAnswer = answerCompiler.compile(second);
        JsonNode json = lockedAnswerJson(firstAnswer);
        JsonNode secondJson = lockedAnswerJson(secondAnswer);

        assertThat(validator.validate(first).valid()).isTrue();
        assertThat(first.contractHash()).isEqualTo(second.contractHash());
        assertThat(first.graphViewHash()).isEqualTo(second.graphViewHash());
        assertThat(firstAnswer).isEqualTo(secondAnswer);
        assertThat(json.get("protocolHash").asText()).isEqualTo(secondJson.get("protocolHash").asText());
        assertThat(json.at("/executionSpec/steps")).hasSize(1);
        assertThat(json.at("/evidence/direct")).hasSize(1);
        assertThat(json.at("/evidence/supporting")).hasSize(1);
        assertThat(json.at("/executionDag/nodes")).hasSize(2);
        assertThat(json.at("/executionDag/edges")).hasSize(0);
        assertJsonFieldOrderIsStable(firstAnswer);
        assertStepBindings(first);
    }

    @Test
    void goldenCaseInsufficientEvidenceStillProducesParseableLockedJson() throws Exception {
        EvidenceExecutionContract contract = compile(List.of(chunk(
            "broken",
            "doc://broken#chunk=0",
            "select from",
            0.95
        )));
        String lockedAnswer = answerCompiler.compile(contract);
        JsonNode json = lockedAnswerJson(lockedAnswer);

        assertThat(validator.validate(contract).valid()).isTrue();
        assertThat(contract.decision()).isEqualTo(EvidenceExecutionDecision.EMPTY_RESULT);
        assertThat(contract.pathState()).isEqualTo(EvidencePathState.WEAK_PATH);
        assertThat(contract.executable()).isFalse();
        assertThat(json.get("type").asText()).isEqualTo("evidence_reasoning_v2");
        assertThat(json.get("pathState").asText()).isEqualTo("WEAK_PATH");
        assertThat(json.at("/result/exists").asBoolean()).isTrue();
        assertThat(json.at("/result/mode").asText()).isEqualTo("PRELIMINARY");
        assertThat(json.at("/result/conclusion").asText()).isNotBlank();
        assertThat(json.at("/result/uncertainty").asText()).isNotBlank();
        assertThat(json.at("/result/claimGraph/primaryClaimId").asText()).isEqualTo("claim-1");
        assertThat(json.at("/result/answer").asText()).contains("Conclusion:");
        assertThat(json.at("/result/answer").asText()).contains("Uncertainty:");
        assertThat(json.at("/result/answer").asText()).doesNotContain("Evidence facts:");
        assertThat(json.at("/result/claims/0/sourceRef").asText()).isEqualTo("doc://broken#chunk=0");
        assertThat(json.at("/reasoningTrace/pathDecision/pathState").asText()).isEqualTo("WEAK_PATH");
        assertThat(json.at("/reasoningTrace/pathDecision/decision").asText()).contains("below decision threshold");
        assertThat(json.at("/executionSpec/steps")).hasSize(4);
        assertThat(json.at("/executionDag/nodes")).hasSize(4);
        assertThat(json.at("/executionDag/edges")).hasSize(3);
        assertThat(lockedAnswer).contains("Uncertainty:");
        assertStepBindings(contract);
    }

    @Test
    void validatorRejectsBrokenStepBindingsAndConfidenceRanges() {
        EvidenceExecutionContract contract = new EvidenceExecutionContract(
            EvidenceExecutionContract.CONTRACT_VERSION,
            EvidenceExecutionDecision.ANSWER_ALLOWED,
            EvidencePathState.STRONG_PATH,
            List.of("missing-node"),
            List.of(),
            List.of("doc://ok#chunk=0"),
            List.of(),
            List.of(),
            new EvidenceExecutionContract.ExecutionSpec("execution_spec", List.of(
                new EvidenceExecutionContract.ExecutionStep(1, "missing-node", "broken", "doc://missing#chunk=0", 1.2)
            )),
            new EvidenceExecutionContract.EvidenceTiers(
                List.of(new EvidenceExecutionContract.EvidenceItem("n1", "doc://ok#chunk=0", "DOC_CHUNK", "ok", 0.9)),
                List.of(),
                List.of()
            ),
            new EvidenceExecutionContract.ExecutionDag(
                List.of(new EvidenceExecutionContract.DagNode("n1", "DOC_CHUNK", "ok", "doc://ok#chunk=0", 0.9)),
                List.of()
            ),
            "graph-hash",
            "contract-hash",
            true,
            true
        );

        EvidenceExecutionContractValidator.ValidationResult result = validator.validate(contract);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
            .anySatisfy(error -> assertThat(error).contains("missing DAG node"))
            .anySatisfy(error -> assertThat(error).contains("missing evidence binding"))
            .anySatisfy(error -> assertThat(error).contains("0..1"));
    }

    private EvidenceExecutionContract compile(List<EvidenceChunk> chunks) {
        EvidenceGraph graph = graphEngine.build("query:golden", chunks);
        EvidenceExecutionReport report = pathExecutor.execute(graph);
        return compiler.compile(graph, report);
    }

    private EvidenceChunk chunk(String docId, String refId, String content, double score) {
        return new EvidenceChunk(
            EvidenceType.DOCUMENT,
            EvidenceChunk.CONTRACT_VERSION,
            new EvidenceSource(docId + ".md", null, null, docId, "Body"),
            content,
            score,
            Map.of("refId", refId, "evidenceGrade", "A"),
            new EvidenceGovernance("tenant", "user", List.of(), "ALLOWED"),
            Map.of()
        );
    }

    private JsonNode lockedAnswerJson(String lockedAnswer) throws Exception {
        int fenceStart = lockedAnswer.indexOf("```json");
        int jsonStart = lockedAnswer.indexOf('\n', fenceStart) + 1;
        int jsonEnd = lockedAnswer.indexOf("```", jsonStart);
        assertThat(fenceStart).isGreaterThanOrEqualTo(0);
        assertThat(jsonStart).isGreaterThan(0);
        assertThat(jsonEnd).isGreaterThan(jsonStart);
        return objectMapper.readTree(lockedAnswer.substring(jsonStart, jsonEnd));
    }

    private void assertStepBindings(EvidenceExecutionContract contract) {
        Set<String> dagNodes = new LinkedHashSet<>(
            contract.executionDag().nodes().stream().map(EvidenceExecutionContract.DagNode::id).toList()
        );
        Set<String> evidenceRefs = new LinkedHashSet<>();
        contract.evidence().direct().forEach(item -> evidenceRefs.add(item.refId()));
        contract.evidence().supporting().forEach(item -> evidenceRefs.add(item.refId()));
        contract.evidence().context().forEach(item -> evidenceRefs.add(item.refId()));

        assertThat(contract.executionSpec().steps()).isNotEmpty();
        contract.executionSpec().steps().forEach(step -> {
            assertThat(dagNodes).contains(step.nodeId());
            assertThat(evidenceRefs).contains(step.source());
            assertThat(step.confidence()).isBetween(0.0, 1.0);
        });
    }

    private void assertReasoningProtocolContract(JsonNode json) {
        assertThat(json.get("type").asText()).isEqualTo("evidence_reasoning_v2");
        assertThat(json.get("protocolVersion").asText()).isEqualTo(DeterministicAnswerCompiler.REASONING_PROTOCOL_VERSION);
        assertThat(json.get("contractVersion").asText()).isEqualTo(EvidenceExecutionContract.CONTRACT_VERSION);
        assertThat(json.get("protocolHash").asText()).matches("[0-9a-f]{64}");
        List.of(
            "type",
            "protocolVersion",
            "contractVersion",
            "protocolHash",
            "pathState",
            "contractHash",
            "graphViewHash",
            "decision",
            "result",
            "executionSpec",
            "evidence",
            "executionDag",
            "trustedSql",
            "deterministicFacts",
            "reasoningTrace"
        ).forEach(field -> {
            assertThat(json.has(field)).as("required field " + field).isTrue();
            assertThat(json.get(field).isNull()).as("required field " + field + " is not null").isFalse();
        });
    }

    private void assertJsonFieldOrderIsStable(String lockedAnswer) {
        int type = lockedAnswer.indexOf("\"type\"");
        int protocolVersion = lockedAnswer.indexOf("\"protocolVersion\"");
        int contractVersion = lockedAnswer.indexOf("\"contractVersion\"");
        int protocolHash = lockedAnswer.indexOf("\"protocolHash\"");
        int contractHash = lockedAnswer.indexOf("\"contractHash\"");
        int executionSpec = lockedAnswer.indexOf("\"executionSpec\"");
        int evidence = lockedAnswer.indexOf("\"evidence\"");
        int executionDag = lockedAnswer.indexOf("\"executionDag\"");
        int reasoningTrace = lockedAnswer.indexOf("\"reasoningTrace\"");

        assertThat(type).isLessThan(protocolVersion);
        assertThat(protocolVersion).isLessThan(contractVersion);
        assertThat(contractVersion).isLessThan(protocolHash);
        assertThat(protocolHash).isLessThan(contractHash);
        assertThat(contractVersion).isLessThan(contractHash);
        assertThat(contractHash).isLessThan(executionSpec);
        assertThat(executionSpec).isLessThan(evidence);
        assertThat(evidence).isLessThan(executionDag);
        assertThat(executionDag).isLessThan(reasoningTrace);
    }
}

