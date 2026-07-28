package com.chatchat.mcpserver.metadata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "chatchat.mcp.enterprise-metadata")
public class EnterpriseMetadataProperties {

    private boolean enabled = true;
    private String sourceLocationPattern = "";
    private List<String> sourceLocationPatterns = new ArrayList<>(List.of(
        "file:../标准字段词根/**/*.xlsx",
        "file:../../标准字段词根/**/*.xlsx"
    ));
    private String indexName = "enterprise_metadata_catalog";
    private int defaultLimit = 20;
    private int maxResults = 100;
    private boolean refreshOnStartup = true;
    private Knn knn = new Knn();
    private ScenarioClassification scenarioClassification = new ScenarioClassification();

    public List<String> resolvedSourceLocationPatterns() {
        if (sourceLocationPattern != null && !sourceLocationPattern.isBlank()) {
            return List.of(sourceLocationPattern.trim());
        }
        return sourceLocationPatterns == null ? List.of() : sourceLocationPatterns.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    @Data
    public static class Knn {
        private boolean enabled = true;
        private String vectorField = "scenarioVector";
        private int dimension = 256;
        private int candidateLimit = 100;
        private double bm25Weight = 0.55D;
        private double vectorWeight = 0.45D;
    }

    @Data
    public static class ScenarioClassification {
        private boolean enabled = true;
        private String provider = "database";
        private int cacheTtlSeconds = 300;
        private int maxScenariosPerRecord = 3;
    }
}
