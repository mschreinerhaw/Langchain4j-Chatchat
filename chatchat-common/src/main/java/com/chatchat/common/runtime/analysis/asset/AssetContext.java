package com.chatchat.common.runtime.analysis.asset;

import java.util.Map;

/** A published template, not an executed dataset. Unknown facts stay absent. */
public record AssetContext(String assetId, String type, String name, String description,
                           Map<String, Object> technicalMetadata,
                           Map<String, Object> usageMetadata,
                           Map<String, Object> qualityMetadata,
                           String sourceTool, Map<String, Object> provenance) {
    public AssetContext {
        if (assetId == null || assetId.isBlank() || sourceTool == null || sourceTool.isBlank())
            throw new IllegalArgumentException("Asset identity and evidence source are required");
        name = name == null || name.isBlank() ? assetId : name;
        description = description == null ? "" : description;
        technicalMetadata = technicalMetadata == null ? Map.of() : Map.copyOf(technicalMetadata);
        usageMetadata = usageMetadata == null ? Map.of() : Map.copyOf(usageMetadata);
        qualityMetadata = qualityMetadata == null ? Map.of() : Map.copyOf(qualityMetadata);
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }
}
