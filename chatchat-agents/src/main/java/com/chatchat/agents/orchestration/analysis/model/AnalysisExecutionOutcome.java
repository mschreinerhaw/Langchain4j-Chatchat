package com.chatchat.agents.orchestration.analysis.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain-neutral terminal state of the governed analysis pipeline.
 *
 * <p>This contract keeps final response rendering independent from raw tool results. A failed
 * analysis can therefore describe its state, recovery route and unresolved gaps without turning
 * the supporting dataset into the primary answer.</p>
 */
public record AnalysisExecutionOutcome(
    String schemaVersion,
    ExecutionStatus status,
    FailureCategory failureCategory,
    PhaseStatus dataStatus,
    PhaseStatus workerStatus,
    PhaseStatus reductionStatus,
    PhaseStatus synthesisStatus,
    PhaseStatus governanceStatus,
    List<Map<String, Object>> unresolvedGaps,
    RetryDirective retryDirective,
    Publishability publishability,
    String reason
) {

    public static final String SCHEMA_VERSION = "analysis_execution_outcome.v1";

    public AnalysisExecutionOutcome {
        schemaVersion = SCHEMA_VERSION;
        status = status == null ? ExecutionStatus.EXECUTION_FAILED : status;
        failureCategory = failureCategory == null ? FailureCategory.NONE : failureCategory;
        dataStatus = dataStatus == null ? PhaseStatus.NOT_STARTED : dataStatus;
        workerStatus = workerStatus == null ? PhaseStatus.NOT_STARTED : workerStatus;
        reductionStatus = reductionStatus == null ? PhaseStatus.NOT_STARTED : reductionStatus;
        synthesisStatus = synthesisStatus == null ? PhaseStatus.NOT_STARTED : synthesisStatus;
        governanceStatus = governanceStatus == null ? PhaseStatus.NOT_STARTED : governanceStatus;
        unresolvedGaps = unresolvedGaps == null ? List.of() : List.copyOf(unresolvedGaps);
        retryDirective = retryDirective == null ? RetryDirective.none() : retryDirective;
        publishability = publishability == null
            ? Publishability.GOVERNED_FAILURE_REPORT_ONLY : publishability;
        reason = reason == null ? "" : reason.trim();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("status", status.name());
        value.put("failureCategory", failureCategory.name());
        value.put("dataStatus", dataStatus.name());
        value.put("workerStatus", workerStatus.name());
        value.put("reductionStatus", reductionStatus.name());
        value.put("synthesisStatus", synthesisStatus.name());
        value.put("governanceStatus", governanceStatus.name());
        value.put("unresolvedGaps", unresolvedGaps);
        value.put("retryDirective", retryDirective.toMap());
        value.put("publishability", publishability.name());
        value.put("reason", reason);
        return Map.copyOf(value);
    }

    public String failureReport() {
        StringBuilder report = new StringBuilder("# 分析未完成\n\n")
            .append("本轮数据获取已完成，但分析结果未达到发布条件。系统未发布未经证据支持的判断；")
            .append("已有数据已保留，可继续用于后续分析。\n\n")
            .append("## 当前状态\n\n")
            .append("- 数据获取：").append(label(dataStatus)).append('\n')
            .append("- Worker 分析：").append(label(workerStatus)).append('\n')
            .append("- 汇总分析：").append(label(reductionStatus)).append('\n')
            .append("- 管理结论：").append(label(synthesisStatus)).append('\n')
            .append("- 发布治理：").append(label(governanceStatus)).append('\n')
            .append("- 支撑数据：已保留为证据附件，不作为失败结果正文\n\n")
            .append("## 未完成原因\n\n")
            .append("- ").append(reasonText()).append('\n');
        if (!unresolvedGaps.isEmpty()) {
            report.append("- 当前存在 ").append(unresolvedGaps.size())
                .append(" 个机器可处理的证据或分析缺口。\n");
        }
        report.append("\n## 下一步\n\n- ").append(nextStep()).append('\n');
        return report.toString();
    }

    private String reasonText() {
        return switch (failureCategory) {
            case NONE -> "分析链路已完成。";
            case DATA_FAILURE -> "数据获取未形成可供分析的完整证据。";
            case ANALYSIS_FAILURE -> "已有数据未形成可准入的 Worker 或 Reducer 分析报告。";
            case GOVERNANCE_REJECTION -> "分析报告未满足证据治理与可审计发布要求。";
            case EVIDENCE_GAP -> "当前证据不足以支持请求中的关键判断。";
        };
    }

    private String nextStep() {
        return switch (status) {
            case NEEDS_REANALYSIS -> "复用已获取的数据，从 "
                + (retryDirective.resumeFrom().isBlank() ? "分析检查点" : retryDirective.resumeFrom())
                + " 重新执行；禁止重新查询相同数据。";
            case NEEDS_MORE_EVIDENCE -> "保留现有数据，由 Gap Planner 根据未解决缺口补充必要证据后继续分析。";
            case INSUFFICIENT_EVIDENCE -> "当前已达到执行边界，保留证据并明确限制，不生成业务判断。";
            case PARTIALLY_COMPLETED -> "基于已通过准入的报告继续处理，其余缺口进入补证或重分析队列。";
            case COMPLETED -> "分析已完成。";
            case EXECUTION_FAILED -> "保留当前检查点，由运行时根据失败阶段决定恢复位置。";
        };
    }

    private String label(PhaseStatus value) {
        return switch (value) {
            case NOT_STARTED -> "未开始";
            case COMPLETED -> "完成";
            case PARTIAL -> "部分完成";
            case FAILED -> "失败";
            case REJECTED -> "未通过准入";
            case BLOCKED -> "阻断";
        };
    }

    public enum ExecutionStatus {
        COMPLETED,
        PARTIALLY_COMPLETED,
        NEEDS_REANALYSIS,
        NEEDS_MORE_EVIDENCE,
        INSUFFICIENT_EVIDENCE,
        EXECUTION_FAILED
    }

    public enum FailureCategory {
        NONE,
        DATA_FAILURE,
        ANALYSIS_FAILURE,
        GOVERNANCE_REJECTION,
        EVIDENCE_GAP
    }

    public enum PhaseStatus {
        NOT_STARTED,
        COMPLETED,
        PARTIAL,
        FAILED,
        REJECTED,
        BLOCKED
    }

    public enum Publishability {
        PUBLISHABLE_ANALYSIS,
        GOVERNED_FAILURE_REPORT_ONLY,
        NOT_PUBLISHABLE
    }

    public record RetryDirective(
        String action,
        String resumeFrom,
        boolean reuseExistingDataset,
        boolean dataAcquisitionAllowed,
        int maximumAdditionalRounds
    ) {
        public RetryDirective {
            action = action == null ? "NONE" : action;
            resumeFrom = resumeFrom == null ? "" : resumeFrom;
            maximumAdditionalRounds = Math.max(0, maximumAdditionalRounds);
        }

        public static RetryDirective none() {
            return new RetryDirective("NONE", "", false, false, 0);
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "action", action,
                "resumeFrom", resumeFrom,
                "reuseExistingDataset", reuseExistingDataset,
                "dataAcquisitionAllowed", dataAcquisitionAllowed,
                "maximumAdditionalRounds", maximumAdditionalRounds);
        }
    }
}
