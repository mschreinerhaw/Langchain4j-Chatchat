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
    void bindsClaimsToEveryChildOfABatchToolResult() {
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            "Docker image count is 18. Active container count is 4.",
            Map.of(),
            List.of(),
            List.of(Map.of(
                "toolName", "linux_command_execute",
                "success", true,
                "evidenceType", "result_set_batch",
                "resultSetEvidence", List.of(
                    Map.of("templateId", "CHECK_DOCKER_IMAGES", "success", true,
                        "outputPreview", "Docker image count is 18"),
                    Map.of("templateId", "CHECK_DOCKER_CONTAINERS", "success", true,
                        "outputPreview", "Active container count is 4")
                )
            ))
        );

        assertThat(result.status()).withFailMessage("%s", result.claimLedger()).isEqualTo("PASS");
        assertThat(result.evidenceManifest().toString())
            .contains("#result=1/child=1", "#result=1/child=2", "Docker image count is 18", "Active container count is 4");
    }

    @Test
    void bindsOneCompoundClaimToMultipleBatchChildren() {
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            "Docker image count is 18 and active Docker container count is 4.",
            Map.of(), List.of(), List.of(Map.of(
                "toolName", "linux_command_execute",
                "success", true,
                "resultSetEvidence", List.of(
                    Map.of("templateId", "CHECK_IMAGES", "success", true,
                        "outputPreview", "Docker image count is 18"),
                    Map.of("templateId", "CHECK_CONTAINERS", "success", true,
                        "outputPreview", "active Docker container count is 4")
                )
            ))
        );

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.evidenceManifest()).containsEntry("evidenceCount", 2);
        assertThat(result.claimLedger().toString())
            .contains("#result=1/child=1", "#result=1/child=2", "VERIFIED_VALUE_MATCH");
    }

    @Test
    void doesNotVerifyAggregateCountsFromUnrelatedNumbersElsewhereInLargeResults() {
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            "Container status lists 12 containers, including 4 Up and 8 Exited.",
            Map.of(), List.of(), List.of(Map.of(
                "toolName", "command_execute",
                "success", true,
                "resultSetEvidence", List.of(Map.of(
                    "templateId", "CONTAINER_STATUS",
                    "success", true,
                    "outputPreview", "CONTAINER ID STATUS NAME\nabc Up 4 weeks service-a\ndef Exited (8) service-b\nworker pid 12"
                ))
            ))
        );

        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.claimLedger().toString()).contains("UNBOUND");
    }

    @Test
    void requiresTheAggregateCountPhraseRatherThanScatteredMatchingDigits() {
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            "There are 12 containers, including 4 active containers and 8 exited containers.",
            Map.of(), List.of(), List.of(Map.of(
                "toolName", "command_execute", "success", true,
                "outputPreview", "container-a Up 4 weeks\ncontainer-b Exited (8)\nworker pid 12"
            ))
        );

        assertThat(result.status()).isEqualTo("FAIL");
        assertThat(result.criticalUnboundClaims()).isEqualTo(1);
    }

    @Test
    void treatsOperationalSafetyGuidanceAsGuidanceRatherThanAnUnboundFact() {
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            "In production, you must back up data and avoid deleting active resources.",
            Map.of(), List.of(), List.of(Map.of(
                "toolName", "command_execute", "success", true, "outputPreview", "runtime data"
            ))
        );

        assertThat(result.criticalUnboundClaims()).isZero();
        assertThat(result.status()).isEqualTo("PARTIAL");
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
