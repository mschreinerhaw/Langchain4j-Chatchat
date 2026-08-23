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
    void acceptsExactToolEvidenceReferencesAsFirstClassCitations() {
        String ref = "tool://http_request_execute#result=1/child=2";
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            "3 个 NodeManager 节点均处于 RUNNING 状态 [evidence: " + ref + "]。",
            Map.of(), List.of(), List.of(Map.of(
                "toolName", "http_request_execute",
                "success", true,
                "resultSetEvidence", List.of(
                    Map.of("templateId", "cluster_metrics", "success", true,
                        "outputPreview", "activeNodes=3"),
                    Map.of("templateId", "node_status", "success", true,
                        "outputPreview", "nodes=[RUNNING,RUNNING,RUNNING]")
                )
            ))
        );

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.claimLedger().toString())
            .contains("EXPLICIT_REFERENCE", ref);
    }

    @Test
    void bindsFactualSectionsToTheReturnedToolNamedByTheSection() {
        List<Map<String, Object>> evidence = List.of(Map.of(
            "toolName", "http_request_execute",
            "success", true,
            "resultSetEvidence", List.of(
                Map.of("templateId", "http_query_cluster_metrics", "success", true,
                    "outputPreview", "{\"availableMB\":98304,\"appsRunning\":0}"),
                Map.of("templateId", "http_query_nodes", "success", true,
                    "outputPreview", "{\"nodes\":[{\"state\":\"RUNNING\"},{\"state\":\"RUNNING\"},{\"state\":\"RUNNING\"}]}"))
        ));
        String answer = """
            ## 关键证据
            ### 节点状态 [证据来源：http_query_nodes]
            3 个 NodeManager 节点均处于 `RUNNING` 状态。

            ## 操作建议
            ```bash
            yarn node -list
            ```
            建议修改配置前先备份。
            """;

        AnswerEvidenceLedgerCompiler.BindingResult binding = compiler.bindReturnedEvidence(
            answer, Map.of(), List.of(), evidence);
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            binding.answer(), Map.of(), List.of(), evidence);

        assertThat(binding.boundClaimCount()).isEqualTo(1);
        assertThat(binding.answer())
            .contains("tool://http_request_execute#result=1/child=2")
            .doesNotContain("yarn node -list [evidence:")
            .doesNotContain("先备份。 [evidence:");
        assertThat(result.status()).withFailMessage("%s", result.claimLedger()).isEqualTo("PASS");
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
        assertThat(result.status()).isEqualTo("PASS");
    }

    @Test
    void normalizesOpaqueToolAliasesToTheMatchingReturnedResultSet() {
        List<Map<String, Object>> evidence = List.of(Map.of(
            "toolName", "http_request_execute", "success", true,
            "resultSetEvidence", List.of(
                Map.of("templateId", "cluster_metrics", "success", true,
                    "outputPreview", "{\"totalMB\":98304,\"appsRunning\":0,\"activeNodes\":3}"),
                Map.of("templateId", "node_status", "success", true,
                    "outputPreview", "{\"nodeHostName\":\"worker11\",\"state\":\"RUNNING\",\"version\":\"2.6.0\"}"))
        ));
        String answer = """
            ## 现象总结
            当前无运行任务 [tool://http_request_execute/11111111-1111-1111-1111-111111111111]。
            3 个节点正常运行 [tool://http_request_execute/22222222-2222-2222-2222-222222222222]。
            | totalMB | 98304 [tool://http_request_execute/11111111-1111-1111-1111-111111111111] |
            | node | worker11 RUNNING [tool://http_request_execute/22222222-2222-2222-2222-222222222222] |
            """;

        AnswerEvidenceLedgerCompiler.BindingResult binding = compiler.bindReturnedEvidence(
            answer, Map.of(), List.of(), evidence);
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            binding.answer(), Map.of(), List.of(), evidence);

        assertThat(binding.answer())
            .doesNotContain("tool://http_request_execute/11111111")
            .doesNotContain("tool://http_request_execute/22222222")
            .contains("tool://http_request_execute#result=1/child=1")
            .contains("tool://http_request_execute#result=1/child=2");
        assertThat(result.status()).withFailMessage("%s", result.claimLedger()).isEqualTo("PASS");
        assertThat(result.unknownReferences()).isZero();
    }

    @Test
    void normalizesSingleSegmentOpaqueAliasesByReturnedContent() {
        List<Map<String, Object>> evidence = List.of(Map.of(
            "toolName", "http_request_execute", "success", true,
            "resultSetEvidence", List.of(
                Map.of("templateId", "cluster_metrics", "success", true,
                    "outputPreview", "{\"totalMB\":98304,\"appsRunning\":0,\"activeNodes\":3}"),
                Map.of("templateId", "node_status", "success", true,
                    "outputPreview", "{\"nodeHostName\":\"worker11\",\"state\":\"RUNNING\",\"version\":\"2.6.0\"}"))
        ));
        String answer = """
            ## Summary
            totalMB is 98304 and appsRunning is 0 [tool://11111111-1111-1111-1111-111111111111].
            node worker11 is RUNNING on version 2.6.0 [tool://22222222-2222-2222-2222-222222222222].
            """;

        AnswerEvidenceLedgerCompiler.BindingResult binding = compiler.bindReturnedEvidence(
            answer, Map.of(), List.of(), evidence);
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            binding.answer(), Map.of(), List.of(), evidence);

        assertThat(binding.answer())
            .doesNotContain("tool://11111111")
            .doesNotContain("tool://22222222")
            .contains("tool://http_request_execute#result=1/child=1")
            .contains("tool://http_request_execute#result=1/child=2");
        assertThat(binding.boundClaimCount()).isEqualTo(2);
        assertThat(result.status()).withFailMessage("%s", result.claimLedger()).isEqualTo("PASS");
    }

    @Test
    void normalizesRuntimeEvidenceIdAliasesToCanonicalResultUris() {
        List<Map<String, Object>> evidence = List.of(
            Map.of("toolName", "http_capability_query", "success", true,
                "outputPreview", "catalog templates only"),
            Map.of("toolName", "http_request_execute", "success", true,
                "resultSetEvidence", List.of(
                    Map.of("templateId", "cluster_metrics", "success", true,
                        "outputPreview", "{\"allocatedMB\":0,\"appsRunning\":0,\"totalNodes\":3}"),
                    Map.of("templateId", "node_status", "success", true,
                        "outputPreview", "{\"nodeHostName\":\"worker11\",\"state\":\"RUNNING\"}")))
        );
        String alias = "tenant:run:http_request_execute:call-1:digest";
        String answer = """
            ## 现象总结
            allocatedMB=0 且 appsRunning=0 [evidenceId=%s]。
            3 个 NodeManager 全部在线且健康。
            """.formatted(alias);

        AnswerEvidenceLedgerCompiler.BindingResult binding = compiler.bindReturnedEvidence(
            answer, Map.of(), List.of(), evidence);
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            binding.answer(), Map.of(), List.of(), evidence);

        assertThat(binding.answer())
            .doesNotContain("evidenceId=")
            .contains("tool://http_request_execute#result=2/child=1");
        assertThat(binding.boundClaimCount()).isEqualTo(2);
        assertThat(result.status()).withFailMessage("%s", result.claimLedger()).isEqualTo("PASS");
    }

    @Test
    void replacesDiscoveryReferencesWhenRuntimeResultSetsAreAvailable() {
        List<Map<String, Object>> evidence = List.of(
            Map.of("toolName", "capability_query", "success", true,
                "outputPreview", "available templates"),
            Map.of("toolName", "execute", "success", true,
                "resultSetEvidence", List.of(Map.of(
                    "templateId", "metrics", "success", true,
                    "outputPreview", "{\"availableMB\":98304,\"appsRunning\":0}")))
        );
        String answer = "当前 availableMB=98304 且 appsRunning=0 "
            + "[evidence: tool://capability_query#result=1]。";

        AnswerEvidenceLedgerCompiler.BindingResult binding = compiler.bindReturnedEvidence(
            answer, Map.of(), List.of(), evidence);
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            binding.answer(), Map.of(), List.of(), evidence);

        assertThat(binding.answer())
            .doesNotContain("tool://capability_query#result=1")
            .contains("tool://execute#result=2/child=1");
        assertThat(result.status()).isEqualTo("PASS");
    }

    @Test
    void bindsEachSentenceOnAProseLineAndIgnoresMarkdownTableUrls() {
        String answer = """
            ## 直接结论
            当前集群无活跃任务。集群共有 98304 MB、96 vCore 和 3 个活跃节点。

            | 项目 | 地址 |
            |---|---|
            | 端点 | `http://rm-host:8088` |
            """;
        List<Map<String, Object>> evidence = List.of(Map.of(
            "toolName", "execute", "success", true,
            "resultSetEvidence", List.of(Map.of(
                "templateId", "metrics", "success", true,
                "outputPreview", "{\"cluster\":\"active\",\"appsRunning\":0,"
                    + "\"totalMB\":98304,\"totalVirtualCores\":96,\"activeNodes\":3}"))
        ));

        AnswerEvidenceLedgerCompiler.BindingResult binding = compiler.bindReturnedEvidence(
            answer, Map.of(), List.of(), evidence);
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            binding.answer(), Map.of(), List.of(), evidence);

        assertThat(binding.boundClaimCount()).isEqualTo(2);
        assertThat(result.status()).withFailMessage("%s", result.claimLedger()).isEqualTo("PASS");
        assertThat(result.claimLedger()).containsEntry("materialClaimCount", 2);
        assertThat(result.unknownReferences()).isZero();
    }

    @Test
    void verifiesDerivedPercentagesOnlyWhenReturnedOperandsSupportTheRatio() {
        List<Map<String, Object>> evidence = List.of(Map.of(
            "toolName", "metrics", "success", true,
            "outputPreview", "{\"allocatedMB\":0,\"totalMB\":98304}"
        ));
        AnswerEvidenceLedgerCompiler.BindingResult validBinding = compiler.bindReturnedEvidence(
            "内存使用率为 0%（已分配 0 MB / 总量 98304 MB）。", Map.of(), List.of(), evidence);
        AnswerEvidenceLedgerCompiler.Result valid = compiler.compile(
            validBinding.answer(), Map.of(), List.of(), evidence);
        AnswerEvidenceLedgerCompiler.Result invalid = compiler.compile(
            "内存使用率为 50%（已分配 0 MB / 总量 98304 MB）。", Map.of(), List.of(), evidence);

        assertThat(valid.status()).withFailMessage("%s", valid.claimLedger()).isEqualTo("PASS");
        assertThat(validBinding.answer()).contains("tool://metrics#result=1");
        assertThat(invalid.status()).isEqualTo("FAIL");
    }

    @Test
    void verifiesDisplayedPercentageFromGenericStructuredMetricRoles() {
        List<Map<String, Object>> evidence = List.of(Map.of(
            "toolName", "metrics", "success", true,
            "outputPreview", "{\"system\":\"YARN resource\",\"allocatedMB\":0,\"totalMB\":98304}"
        ));
        AnswerEvidenceLedgerCompiler.BindingResult validBinding = compiler.bindReturnedEvidence(
            "YARN resource utilization is 0%.", Map.of(), List.of(), evidence);
        AnswerEvidenceLedgerCompiler.BindingResult invalidBinding = compiler.bindReturnedEvidence(
            "YARN resource utilization is 50%.", Map.of(), List.of(), evidence);
        AnswerEvidenceLedgerCompiler.Result valid = compiler.compile(
            validBinding.answer(), Map.of(), List.of(), evidence);
        AnswerEvidenceLedgerCompiler.Result invalid = compiler.compile(
            invalidBinding.answer(), Map.of(), List.of(), evidence);

        assertThat(validBinding.boundClaimCount()).isEqualTo(1);
        assertThat(valid.status()).withFailMessage("%s", valid.claimLedger()).isEqualTo("PASS");
        assertThat(invalidBinding.boundClaimCount()).isZero();
        assertThat(invalid.status()).isEqualTo("FAIL");
    }

    @Test
    void excludesOperationalAndSourceAppendicesFromTheFactualLedger() {
        String answer = """
            ## 现象总结
            当前可用内存为 98304 MB [tool://http_request_execute#result=1/child=1]。

            ## 排查步骤
            1. 检查调度器队列容量配置是否正常。

            ## 验证命令
            curl http://rm-host:8088/ws/v1/cluster/scheduler

            ## 来源
            集群指标：`http://rm-host:8088/ws/v1/cluster/metrics`
            """;
        List<Map<String, Object>> evidence = List.of(Map.of(
            "toolName", "http_request_execute", "success", true,
            "resultSetEvidence", List.of(Map.of(
                "templateId", "cluster_metrics", "success", true,
                "outputPreview", "{\"availableMB\":98304}"))
        ));

        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(answer, Map.of(), List.of(), evidence);

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.claimLedger()).containsEntry("materialClaimCount", 1);
        assertThat(result.unknownReferences()).isZero();
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
    void excludesCircularCoverageAnnotationsAndOptionalAdvice() {
        String answer = """
            资源总量为 98304 MB。
            **证据覆盖率：50%**（1/2 必需检查完成）
            本次分析的 evidence coverage 为 50%。
            **弹性伸缩**：如长期低负载，可考虑动态缩容节省成本
            **若空闲为非预期状态**：
            """;
        List<Map<String, Object>> evidence = List.of(Map.of(
            "toolName", "metrics", "success", true,
            "outputPreview", "{\"totalMB\":98304}"
        ));

        AnswerEvidenceLedgerCompiler.BindingResult binding = compiler.bindReturnedEvidence(
            answer, Map.of(), List.of(), evidence);
        AnswerEvidenceLedgerCompiler.Result result = compiler.compile(
            binding.answer(), Map.of(), List.of(), evidence);

        assertThat(result.status()).withFailMessage("%s", result.claimLedger()).isEqualTo("PASS");
        assertThat(result.claimLedger()).containsEntry("materialClaimCount", 1);
        assertThat(result.claimLedger()).containsEntry("verifiedClaimCount", 1);
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
