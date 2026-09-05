package com.chatchat.agents.orchestration.analysis.dispatch;

import com.chatchat.common.runtime.summary.model.ModelSummaryProgress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projects internal analysis execution progress into a stable business-facing event contract. */
public final class BusinessAnalysisProgressProjector {

    private BusinessAnalysisProgressProjector() {
    }

    public static Map<String, Object> metadata(ModelSummaryProgress progress) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", "business_analysis_progress");
        metadata.put("stage", stage(progress.stage()));
        metadata.put("progressId", java.util.UUID.nameUUIDFromBytes(
            progress.taskId().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        metadata.put("workReference", progress.workReference());
        metadata.put("workIndex", progress.workIndex());
        metadata.put("workCount", progress.workCount());
        metadata.put("occurredAtEpochMs", progress.occurredAtEpochMs());
        for (String field : List.of(
            "chunkIndex", "chunkCount", "recordFrom", "recordTo", "elapsedMs", "durationMs")) {
            Object value = progress.details().get(field);
            if (value != null) {
                metadata.put(field, value);
            }
        }
        return metadata;
    }

    public static String stage(String internalStage) {
        return switch (internalStage) {
            case "WORKER_CLAIMED" -> "DATA_PREPARATION_STARTED";
            case "WORKER_HEARTBEAT" -> "DATA_PROCESSING_ACTIVE";
            case "CHUNK_STARTED" -> "DATA_BATCH_STARTED";
            case "CHUNK_COMPLETED" -> "DATA_BATCH_COMPLETED";
            case "CHUNK_RETRY" -> "DATA_BATCH_RETRYING";
            case "DATASET_REDUCING" -> "RESULT_AGGREGATING";
            case "DATASET_REDUCTION_RETRY" -> "RESULT_AGGREGATION_RETRYING";
            case "DATASET_COMPLETED" -> "DATA_PROCESSING_COMPLETED";
            case "DATASET_CANCELLED" -> "DATA_PROCESSING_CANCELLED";
            case "DATASET_FAILED" -> "DATA_PROCESSING_FAILED";
            default -> "DATA_PROCESSING_UPDATED";
        };
    }

    public static String content(ModelSummaryProgress progress) {
        String position = progress.workIndex() + "/" + progress.workCount();
        Object chunkIndex = progress.details().get("chunkIndex");
        Object chunkCount = progress.details().get("chunkCount");
        return switch (progress.stage()) {
            case "WORKER_CLAIMED" -> "正在准备第 " + position + " 组业务数据。";
            case "WORKER_HEARTBEAT" -> "正在处理第 " + position + " 组业务数据。";
            case "CHUNK_STARTED" -> "正在处理第 " + position + " 组业务数据，当前进度 "
                + chunkIndex + "/" + chunkCount + "。";
            case "CHUNK_COMPLETED" -> "第 " + position + " 组业务数据已完成阶段 "
                + chunkIndex + "/" + chunkCount + "。";
            case "CHUNK_RETRY" -> "第 " + position + " 组业务数据正在重新处理阶段 "
                + chunkIndex + "/" + chunkCount + "。";
            case "DATASET_REDUCING" -> "正在汇总第 " + position + " 组业务数据的分析结果。";
            case "DATASET_REDUCTION_RETRY" -> "正在重新汇总第 " + position + " 组业务数据。";
            case "DATASET_COMPLETED" -> "第 " + position + " 组业务数据处理完成。";
            case "DATASET_CANCELLED" -> "第 " + position + " 组业务数据处理已取消。";
            case "DATASET_FAILED" -> "第 " + position + " 组业务数据处理未完成。";
            default -> "第 " + position + " 组业务数据处理进度已更新。";
        };
    }
}
