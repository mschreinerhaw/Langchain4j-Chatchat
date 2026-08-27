package com.chatchat.agents.runtime.evaluation;

import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.run.AgentRunStatus;
import com.chatchat.common.interaction.InteractionToolTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProductionQualityServiceTest {

    private final AgentProductionQualityService service = new AgentProductionQualityService();
    private final long now = 1_800_000_000_000L;

    @Test
    void aggregatesClaimGroundingToolAndLatencyQuality() {
        AgentRun passed = run("run-pass", "tenant-a", AgentRunStatus.COMPLETED, now - 3_000, 1_000,
            ledger("PASS", 1.0, 0, 0, "grounded"),
            List.of(tool(true), tool(true)));
        AgentRun failed = run("run-fail", "tenant-a", AgentRunStatus.COMPLETED, now - 2_000, 2_000,
            ledger("FAIL", 0.25, 2, 1, "needs_review"),
            List.of(tool(true), tool(false)));
        AgentRun ordinary = run("run-chat", "tenant-a", AgentRunStatus.COMPLETED, now - 1_000, 500,
            ledger("NOT_APPLICABLE", 1.0, 0, 0, "not_evaluated"), List.of());

        AgentProductionQualitySnapshot result = service.summarize(
            List.of(passed, failed, ordinary), "tenant-a", 24, now);

        assertThat(result.contractVersion()).isEqualTo("agent_production_quality_v1");
        assertThat(result.sampledRuns()).isEqualTo(3);
        assertThat(result.auditedRuns()).isEqualTo(3);
        assertThat(result.applicableAudits()).isEqualTo(2);
        assertThat(result.rates())
            .containsEntry("completionRate", 1.0)
            .containsEntry("claimAuditPassRate", 0.5)
            .containsEntry("groundedRate", 0.5)
            .containsEntry("toolSuccessRate", 0.75);
        assertThat(result.measurements())
            .containsEntry("averageClaimCoverage", 0.625)
            .containsEntry("averageLatencyMs", 1166.667)
            .containsEntry("p95LatencyMs", 2000.0);
        assertThat(result.failureReasons()).extracting(AgentProductionQualitySnapshot.FailureReason::code)
            .contains("CLAIM_AUDIT_FAILED", "CRITICAL_CLAIM_UNBOUND", "UNKNOWN_EVIDENCE_REFERENCE",
                "GROUNDING_NEEDS_REVIEW", "TOOL_EXECUTION_FAILED");
        assertThat(result.recentFailures()).singleElement()
            .satisfies(run -> assertThat(run.runId()).isEqualTo("run-fail"));
    }

    @Test
    void enforcesTenantAndTimeWindowInsideTheAggregator() {
        AgentRun tenantA = run("a", "tenant-a", AgentRunStatus.COMPLETED, now - 1_000, 100,
            ledger("PASS", 1.0, 0, 0, "grounded"), List.of());
        AgentRun tenantB = run("b", "tenant-b", AgentRunStatus.COMPLETED, now - 1_000, 100,
            ledger("FAIL", 0.0, 1, 0, "needs_review"), List.of());
        AgentRun expired = run("old", "tenant-a", AgentRunStatus.FAILED, now - 25 * 3_600_000L, 100,
            ledger("FAIL", 0.0, 1, 0, "needs_review"), List.of());

        AgentProductionQualitySnapshot result = service.summarize(
            List.of(tenantA, tenantB, expired), "tenant-a", 24, now);

        assertThat(result.sampledRuns()).isEqualTo(1);
        assertThat(result.tenantId()).isEqualTo("tenant-a");
        assertThat(result.claimStatusCounts()).containsExactly(Map.entry("PASS", 1L));
        assertThat(result.recentFailures()).isEmpty();
    }

    @Test
    void remainsTenantSafeUnderConcurrentWindows() {
        List<AgentRun> runs = IntStream.range(0, 300)
            .mapToObj(index -> run("run-" + index, "tenant-" + index, AgentRunStatus.COMPLETED,
                now - index, 100, ledger("PASS", 1.0, 0, 0, "grounded"), List.of()))
            .toList();

        List<AgentProductionQualitySnapshot> snapshots = IntStream.range(0, 300).parallel()
            .mapToObj(index -> service.summarize(runs, "tenant-" + index, 24, now))
            .toList();

        assertThat(snapshots).hasSize(300).allSatisfy(snapshot -> {
            assertThat(snapshot.sampledRuns()).isEqualTo(1);
            assertThat(snapshot.rates().get("claimAuditPassRate")).isEqualTo(1.0);
        });
        assertThat(snapshots).extracting(AgentProductionQualitySnapshot::tenantId).doesNotHaveDuplicates();
    }

    private AgentRun run(String runId, String tenantId, AgentRunStatus status, long startedAt, long latency,
                         Map<String, Object> metadata, List<InteractionToolTrace> tools) {
        return AgentRun.builder()
            .runId(runId)
            .status(status)
            .request(AgentRunRequest.builder().tenantId(tenantId).skillId("finance-agent").build())
            .result(AgentRunResult.builder()
                .runId(runId)
                .status(status)
                .answer("answer")
                .metadata(metadata)
                .toolTraces(tools)
                .build())
            .metadata(metadata)
            .startedAt(startedAt)
            .finishedAt(startedAt + latency)
            .build();
    }

    private Map<String, Object> ledger(String status, double coverage, int critical, int unknown,
                                       String groundingStatus) {
        return Map.of(
            "claimLedger", Map.of(
                "contractVersion", "claim_ledger_v1",
                "status", status,
                "coverage", coverage,
                "criticalUnboundClaimCount", critical,
                "unknownReferenceCount", unknown
            ),
            "groundingStatus", groundingStatus
        );
    }

    private InteractionToolTrace tool(boolean success) {
        return InteractionToolTrace.builder().toolName("tool").success(success).build();
    }
}
