package com.chatchat.common.runtime.analysis.workflow;

public record AnalysisEntity(String type, String value) {
    public AnalysisEntity {
        type = type == null || type.isBlank() ? "UNKNOWN" : type.trim();
        value = value == null ? "" : value.trim();
    }
}
