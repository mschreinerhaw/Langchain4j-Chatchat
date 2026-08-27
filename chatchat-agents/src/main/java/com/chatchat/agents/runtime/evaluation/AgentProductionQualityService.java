package com.chatchat.agents.runtime.evaluation;

import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.run.AgentRunStatus;
import com.chatchat.common.interaction.InteractionToolTrace;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Produces deterministic production-quality metrics without invoking another model. */
@Component
public class AgentProductionQualityService {

    private static final int MAX_WINDOW_HOURS = 24 * 90;

    public AgentProductionQualitySnapshot summarize(List<AgentRun> runs, String tenantId, int windowHours) {
        return summarize(runs, tenantId, windowHours, System.currentTimeMillis());
    }

    AgentProductionQualitySnapshot summarize(List<AgentRun> runs, String tenantId, int windowHours, long now) {
        int normalizedWindow = Math.max(1, Math.min(windowHours <= 0 ? 24 : windowHours, MAX_WINDOW_HOURS));
        long windowStartedAt = now - normalizedWindow * 3_600_000L;
        List<RunRow> rows = (runs == null ? List.<AgentRun>of() : runs).stream()
            .filter(run -> run != null && run.startedAt() >= windowStartedAt && tenantMatches(run, tenantId))
            .map(this::row)
            .sorted(Comparator.comparingLong(RunRow::startedAt).reversed())
            .toList();

        long terminal = rows.stream().filter(RunRow::terminal).count();
        long completed = rows.stream().filter(row -> "COMPLETED".equals(row.status())).count();
        long audited = rows.stream().filter(row -> row.claimStatus() != null).count();
        List<RunRow> applicable = rows.stream().filter(row -> isApplicable(row.claimStatus())).toList();
        long passedAudits = applicable.stream().filter(row -> "PASS".equals(row.claimStatus())).count();
        List<RunRow> groundingEvaluated = rows.stream()
            .filter(row -> hasText(row.groundingStatus()) && !"not_evaluated".equalsIgnoreCase(row.groundingStatus()))
            .toList();
        long grounded = groundingEvaluated.stream()
            .filter(row -> "grounded".equalsIgnoreCase(row.groundingStatus())).count();
        long toolCalls = rows.stream().mapToLong(RunRow::toolCalls).sum();
        long successfulTools = rows.stream().mapToLong(RunRow::successfulTools).sum();

        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("completionRate", ratio(completed, terminal));
        rates.put("claimAuditPassRate", ratio(passedAudits, applicable.size()));
        rates.put("groundedRate", ratio(grounded, groundingEvaluated.size()));
        rates.put("toolSuccessRate", ratio(successfulTools, toolCalls));

        List<Double> applicableCoverage = applicable.stream()
            .map(RunRow::claimCoverage).filter(value -> value != null).toList();
        List<Long> latency = rows.stream().map(RunRow::latencyMs).filter(value -> value != null).sorted().toList();
        Map<String, Double> measurements = new LinkedHashMap<>();
        measurements.put("averageClaimCoverage", averageDouble(applicableCoverage));
        measurements.put("averageLatencyMs", averageLong(latency));
        measurements.put("p95LatencyMs", percentile95(latency));
        measurements.put("averageToolCalls", rows.isEmpty() ? 0.0D
            : round(rows.stream().mapToLong(RunRow::toolCalls).average().orElse(0.0D)));

        Map<String, Long> statusCounts = counts(rows.stream().map(RunRow::status).toList());
        Map<String, Long> claimStatusCounts = counts(rows.stream().map(RunRow::claimStatus)
            .filter(this::hasText).toList());
        Map<String, Long> failureCounts = new LinkedHashMap<>();
        rows.forEach(row -> row.failureReasons().forEach(reason -> failureCounts.merge(reason, 1L, Long::sum)));
        long totalFailures = failureCounts.values().stream().mapToLong(Long::longValue).sum();
        List<AgentProductionQualitySnapshot.FailureReason> failureReasons = failureCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
            .map(entry -> new AgentProductionQualitySnapshot.FailureReason(
                entry.getKey(), entry.getValue(), ratio(entry.getValue(), totalFailures)))
            .toList();

        List<AgentProductionQualitySnapshot.RunQuality> recentFailures = rows.stream()
            .filter(row -> !row.failureReasons().isEmpty())
            .limit(20)
            .map(RunRow::view)
            .toList();

        return new AgentProductionQualitySnapshot(
            AgentProductionQualitySnapshot.CONTRACT_VERSION,
            text(tenantId),
            now,
            windowStartedAt,
            normalizedWindow,
            rows.size(),
            (int) terminal,
            (int) audited,
            applicable.size(),
            rates,
            measurements,
            statusCounts,
            claimStatusCounts,
            failureReasons,
            trend(rows, normalizedWindow),
            recentFailures
        );
    }

    private RunRow row(AgentRun run) {
        Map<String, Object> metadata = metadata(run);
        Map<String, Object> claimLedger = map(metadataValue(metadata, "claimLedger"));
        String claimStatus = upper(claimLedger.get("status"));
        Double claimCoverage = number(claimLedger.get("coverage"));
        int criticalUnbound = integer(claimLedger.get("criticalUnboundClaimCount"));
        int unknownReferences = integer(claimLedger.get("unknownReferenceCount"));
        String groundingStatus = text(metadataValue(metadata, "groundingStatus"));
        AgentRunResult result = run.result();
        List<InteractionToolTrace> tools = result == null || result.toolTraces() == null
            ? List.of() : result.toolTraces();
        int successfulTools = (int) tools.stream().filter(trace -> trace != null && trace.isSuccess()).count();
        LinkedHashSet<String> failures = new LinkedHashSet<>();
        if (run.status() == AgentRunStatus.FAILED) failures.add("EXECUTION_FAILED");
        if (run.status() == AgentRunStatus.CANCELLED) failures.add("EXECUTION_CANCELLED");
        if ("FAIL".equals(claimStatus)) failures.add("CLAIM_AUDIT_FAILED");
        if ("PARTIAL".equals(claimStatus)) failures.add("CLAIM_COVERAGE_PARTIAL");
        if (criticalUnbound > 0) failures.add("CRITICAL_CLAIM_UNBOUND");
        if (unknownReferences > 0) failures.add("UNKNOWN_EVIDENCE_REFERENCE");
        if ("needs_review".equalsIgnoreCase(groundingStatus)) failures.add("GROUNDING_NEEDS_REVIEW");
        if (tools.stream().anyMatch(trace -> trace != null && !trace.isSuccess())) failures.add("TOOL_EXECUTION_FAILED");
        return new RunRow(
            run.runId(),
            run.request() == null ? "" : text(run.request().getSkillId()),
            run.status() == null ? "UNKNOWN" : run.status().name(),
            run.startedAt(),
            run.finishedAt() == null ? null : Math.max(0L, run.finishedAt() - run.startedAt()),
            claimStatus,
            claimCoverage,
            groundingStatus,
            criticalUnbound,
            unknownReferences,
            tools.size(),
            successfulTools,
            List.copyOf(failures)
        );
    }

    private List<AgentProductionQualitySnapshot.TrendPoint> trend(List<RunRow> rows, int windowHours) {
        boolean hourly = windowHours <= 48;
        Map<Long, List<RunRow>> buckets = new TreeMap<>();
        for (RunRow row : rows) {
            Instant instant = Instant.ofEpochMilli(row.startedAt());
            Instant bucket = hourly
                ? instant.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).toInstant()
                : instant.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            buckets.computeIfAbsent(bucket.toEpochMilli(), ignored -> new ArrayList<>()).add(row);
        }
        return buckets.entrySet().stream().map(entry -> {
            List<RunRow> values = entry.getValue();
            List<RunRow> applicable = values.stream().filter(row -> isApplicable(row.claimStatus())).toList();
            List<Double> coverage = applicable.stream().map(RunRow::claimCoverage)
                .filter(value -> value != null).toList();
            List<Long> latency = values.stream().map(RunRow::latencyMs).filter(value -> value != null).toList();
            return new AgentProductionQualitySnapshot.TrendPoint(
                entry.getKey(),
                values.size(),
                (int) applicable.stream().filter(row -> "PASS".equals(row.claimStatus())).count(),
                (int) applicable.stream().filter(row -> "FAIL".equals(row.claimStatus())).count(),
                averageDouble(coverage),
                averageLong(latency)
            );
        }).toList();
    }

    private boolean tenantMatches(AgentRun run, String tenantId) {
        if (!hasText(tenantId)) return true;
        return run.request() != null && text(tenantId).equals(text(run.request().getTenantId()));
    }

    private Map<String, Object> metadata(AgentRun run) {
        if (run.result() != null && run.result().metadata() != null && !run.result().metadata().isEmpty()) {
            return run.result().metadata();
        }
        return run.metadata() == null ? Map.of() : run.metadata();
    }

    private Object metadataValue(Map<String, Object> metadata, String key) {
        if (metadata == null) return null;
        if (metadata.containsKey(key)) return metadata.get(key);
        return map(metadata.get("agent")).get(key);
    }

    private Map<String, Long> counts(List<String> values) {
        Map<String, Long> result = new LinkedHashMap<>();
        values.stream().filter(this::hasText).forEach(value -> result.merge(value, 1L, Long::sum));
        return result;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private boolean isApplicable(String status) {
        return "PASS".equals(status) || "PARTIAL".equals(status) || "FAIL".equals(status);
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String upper(Object value) {
        String text = text(value);
        return text.isBlank() ? null : text.toUpperCase(Locale.ROOT);
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (!hasText(value)) return null;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int integer(Object value) {
        Double number = number(value);
        return number == null ? 0 : number.intValue();
    }

    private double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0.0D : round((double) numerator / denominator);
    }

    private double averageDouble(List<Double> values) {
        return values == null || values.isEmpty() ? 0.0D
            : round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D));
    }

    private double averageLong(List<Long> values) {
        return values == null || values.isEmpty() ? 0.0D
            : round(values.stream().mapToLong(Long::longValue).average().orElse(0.0D));
    }

    private double percentile95(List<Long> sortedValues) {
        if (sortedValues == null || sortedValues.isEmpty()) return 0.0D;
        int index = Math.max(0, (int) Math.ceil(sortedValues.size() * 0.95D) - 1);
        return sortedValues.get(Math.min(index, sortedValues.size() - 1));
    }

    private double round(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }

    private record RunRow(
        String runId,
        String agentId,
        String status,
        long startedAt,
        Long latencyMs,
        String claimStatus,
        Double claimCoverage,
        String groundingStatus,
        int criticalUnboundClaims,
        int unknownReferences,
        int toolCalls,
        int successfulTools,
        List<String> failureReasons
    ) {
        boolean terminal() {
            return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
        }

        AgentProductionQualitySnapshot.RunQuality view() {
            return new AgentProductionQualitySnapshot.RunQuality(
                runId, agentId, status, startedAt, latencyMs, claimStatus, claimCoverage, groundingStatus,
                toolCalls == 0 ? 0.0D : Math.round((double) successfulTools / toolCalls * 1000.0D) / 1000.0D,
                criticalUnboundClaims, unknownReferences, failureReasons);
        }
    }
}
