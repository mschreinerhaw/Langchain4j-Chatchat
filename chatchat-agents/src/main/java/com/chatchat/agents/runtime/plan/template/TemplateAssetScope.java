package com.chatchat.agents.runtime.plan.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Carries a committed asset selection into template discovery for every workflow family. */
public final class TemplateAssetScope {
    private TemplateAssetScope() { }

    public static Map<String, Object> bind(Map<String, Object> proposedFilters,
                                            List<Map<String, Object>> selectedAssets) {
        if (selectedAssets == null || selectedAssets.isEmpty()) {
            throw new IllegalStateException("ASSET_SELECTION_EMPTY: template discovery requires committed assets");
        }
        Map<String, Object> filters = new LinkedHashMap<>(proposedFilters);
        // These are asset-discovery predicates. Reapplying model-authored aliases, labels or
        // environment constraints after selection can contradict the committed identity.
        for (String key : List.of("assetId", "asset_id", "assetIds", "assetName", "asset_name",
            "name", "toolName", "tool_name", "target", "targetType", "target_type", "cluster", "namespace", "service",
            "database", "databaseType", "dbType", "dialect", "databaseRole", "database_role",
            "technicalType", "technical_type", "labels", "env", "environment")) {
            filters.remove(key);
        }
        List<Object> ids = selectedAssets.stream().map(asset -> asset.get("assetId"))
            .filter(value -> value != null && !value.toString().isBlank()).distinct().toList();
        if (ids.size() == selectedAssets.size()) {
            filters.put("assetIds", ids);
        } else if (selectedAssets.size() > 1) {
            throw new IllegalStateException("ASSET_SELECTION_ID_REQUIRED: multi-asset binding requires returned IDs");
        }
        if (selectedAssets.size() == 1) {
            Map<String, Object> asset = selectedAssets.get(0);
            Object name = asset.get("assetName");
            if (name != null && !name.toString().isBlank()) filters.put("assetName", name);
            else if (ids.isEmpty()) throw new IllegalStateException("ASSET_SELECTION_ID_REQUIRED: missing identity");
            if (asset.get("env") != null) filters.put("env", asset.get("env"));
        }
        return filters;
    }
}
