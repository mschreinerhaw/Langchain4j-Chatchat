package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerEvidenceLedgerCompilerTest {

    private final AnswerEvidenceLedgerCompiler compiler = new AnswerEvidenceLedgerCompiler();

    @Test
    @SuppressWarnings("unchecked")
    void verifiesExplicitClaimReferencesAndBuildsTenantScopedManifest() {
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            "配置变更后必须重启服务 doc://deploy-guide#chunk=3。",
            Map.of("tenantId", "tenant-a", "agentRunId", "run-1"),
            List.of("""
                [Evidence 1]
                citation: doc://deploy-guide#chunk=3
                content: 配置变更后必须重启服务。
                """),
            List.of()
        );

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.coverage()).isEqualTo(1.0);
        assertThat(result.evidenceManifest())
            .containsEntry("contractVersion", "evidence_manifest_v1")
            .containsEntry("tenantId", "tenant-a")
            .containsEntry("runId", "run-1")
            .containsEntry("evidenceCount", 1);
        assertThat(String.valueOf(result.evidenceManifest().get("manifestHash"))).hasSize(64);
        List<Map<String, Object>> claims = (List<Map<String, Object>>) result.claimLedger().get("claims");
        assertThat(claims).singleElement().satisfies(claim -> {
            assertThat(claim).containsEntry("verification", "VERIFIED")
                .containsEntry("bindingMode", "EXPLICIT_REFERENCE");
            assertThat((List<String>) claim.get("evidenceRefs"))
                .containsExactly("doc://deploy-guide#chunk=3");
        });
    }

    @Test
    void failsUnknownReferencesEvenWhenNoEvidenceWasReturned() {
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            "收入增长了 35% doc://missing#chunk=9。",
            Map.of(), List.of(), List.of());

        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.unknownReferences()).isEqualTo(1);
        assertThat(result.claimLedger()).containsEntry("unknownReferenceCount", 1);
    }

    @Test
    void deterministicallyBindsStructuredToolValuesWithoutInventingAUri() {
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            "当前客户数量为 42 家。",
            Map.of("tenantId", "tenant-a"),
            List.of(),
            List.of(Map.of(
                "toolName", "customer_count_query",
                "success", true,
                "outputPreview", "{\"customerLabel\":\"客户\",\"count\":42}"
            ))
        );

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.claimLedger().toString())
            .contains("VERIFIED_VALUE_MATCH", "DETERMINISTIC_VALUE_MATCH", "tool://customer_count_query#result=1");
    }

    @Test
    void rejectsHighRiskValuesThatDoNotOccurInReturnedEvidence() {
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            "当前客户数量为 42 家。",
            Map.of(),
            List.of(),
            List.of(Map.of(
                "toolName", "customer_count_query",
                "success", true,
                "outputPreview", "{\"customerLabel\":\"客户\",\"count\":41}"
            ))
        );

        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.criticalUnboundClaims()).isEqualTo(1);
        assertThat(result.claimLedger()).containsEntry("coverage", 0.0);
    }

    @Test
    void keepsOrdinaryConversationOutsideTheEvidenceGate() {
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            "你好，我可以帮助你梳理这个需求。",
            Map.of(), List.of(), List.of());

        assertThat(result.status()).isEqualTo("NOT_APPLICABLE");
        assertThat(result.coverage()).isEqualTo(1.0);
    }

    @Test
    void compilesConcurrentTenantAnswersWithoutStateLeakage() {
        List<AnswerEvidenceLedgerCompiler.Result> results = IntStream.range(0, 400)
            .parallel()
            .mapToObj(index -> {
                String tenantId = "tenant-" + index;
                String ref = "doc://report-" + index + "#chunk=1";
                return compiler.compile(
                    "租户报告确认完成 " + ref + "。",
                    Map.of("tenantId", tenantId, "agentRunId", "run-" + index),
                    List.of("citation: " + ref + "\ncontent: 租户报告确认完成。"),
                    List.of());
            })
            .toList();

        assertThat(results).hasSize(400).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo("PASS");
            assertThat(result.evidenceManifest().get("evidenceCount")).isEqualTo(1);
        });
        assertThat(results.stream().map(result -> result.evidenceManifest().get("tenantId")))
            .doesNotHaveDuplicates();
        assertThat(results.stream().map(result -> result.evidenceManifest().get("manifestHash")))
            .doesNotHaveDuplicates();
    }
}
