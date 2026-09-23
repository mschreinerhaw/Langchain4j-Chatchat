package com.chatchat.common.runtime.analysis.model;

public record AnalysisEntity(String type, String value) {
    public AnalysisEntity {
        type = type == null || type.isBlank() ? "UNKNOWN" : type.trim();
        value = value == null ? "" : value.trim();
    }
}
