package com.chatchat.agents.tool;

import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilityManifestInjectorTest {
    @Test void preservesPublisherSchemaAndManifestWithoutInventingAliases() {
        var metadata = ToolMetadata.builder().metadata(Map.of(
            "inputSchema", Map.of("type", "object", "properties", Map.of("dataset", Map.of("type", "string"))),
            "mcpToolMeta", Map.of("capabilityManifest", Map.of("operations", java.util.List.of("read_dataset")))))
            .build();
        var prompt = new StringBuilder();
        CapabilityManifestInjector.append(prompt, "authorized_tool", metadata, new ObjectMapper());
        assertThat(prompt.toString()).contains("authorized_tool", "read_dataset", "inputSchema", "dataset")
            .doesNotContain("market_data.fetch", "historical_quotes", "symbols");
    }

    @Test void absentContractsDoNotClaimNewCapabilities() {
        var prompt = new StringBuilder();
        CapabilityManifestInjector.append(prompt, "anything", ToolMetadata.builder().build(), new ObjectMapper());
        assertThat(prompt).isEmpty();
    }
}
