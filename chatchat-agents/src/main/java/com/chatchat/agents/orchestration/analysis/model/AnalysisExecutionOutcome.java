package com.chatchat.agents.orchestration.analysis.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain-neutral terminal state of the governed analysis pipeline.
 *
 * <p>This contract records runtime state and recovery information. Evidence gaps and review
 * observations are advisory metadata for people; they do not grant the runtime authority to
 * accept or reject a business conclusion.</p>
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
        StringBuilder report = new StringBuilder("# 数据分析暂时不可用\n\n")
            .append("本轮数据已经保留，但分析模型没有生成可解析的业务分析正文。")
            .append("这属于分析执行异常，不代表系统否定已有分析或替代人工判断。\n\n")
            .append("## 执行说明\n\n")
            .append("- ").append(reasonText()).append('\n')
            .append("- 证据缺口和不确定性将作为人工复核提示保留，不作为报告发布闸门。\n\n")
            .append("## 后续处理\n\n- ").append(nextStep()).append('\n');
        return report.toString();
    }

    private String reasonText() {
        return switch (failureCategory) {
            case NONE -> "分析链路已完成。";
            case DATA_FAILURE -> "数据获取未形成可供分析的完整证据。";
            case ANALYSIS_FAILURE -> "Worker 或 Reducer 没有返回可解析的分析正文。";
            case GOVERNANCE_REJECTION -> "分析输出包含内部协议或无法解析的技术内容，需要重新生成正文。";
        };
    }

    private String nextStep() {
        return switch (status) {
            case NEEDS_REANALYSIS -> "复用已获取的数据，从 "
                + (retryDirective.resumeFrom().isBlank() ? "分析检查点" : retryDirective.resumeFrom())
                + " 重新执行；禁止重新查询相同数据。";
            case INSUFFICIENT_EVIDENCE -> "基于现有证据继续分析，并在正文中标注限制供人工判断。";
            case PARTIALLY_COMPLETED -> "基于已有报告继续处理，其余缺口作为补证建议保留。";
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
            case REJECTED -> "需要人工复核";
            case BLOCKED -> "未生成";
        };
    }

    public enum ExecutionStatus {
        COMPLETED,
        PARTIALLY_COMPLETED,
        NEEDS_REANALYSIS,
        INSUFFICIENT_EVIDENCE,
        EXECUTION_FAILED
    }

    public enum FailureCategory {
        NONE,
        DATA_FAILURE,
        ANALYSIS_FAILURE,
        GOVERNANCE_REJECTION
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
