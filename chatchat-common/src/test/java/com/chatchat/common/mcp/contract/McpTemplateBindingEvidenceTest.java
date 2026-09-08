package com.chatchat.common.mcp.contract;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpTemplateBindingEvidenceTest {

    @Test
    void distinguishesAbsentBindingFromMalformedBinding() {
        McpTemplateBindingEvidence.ParseResult absent = McpTemplateBindingEvidence.parse(null);
        McpTemplateBindingEvidence.ParseResult malformed = McpTemplateBindingEvidence.parse(
            Map.of("schemaVersion", McpTemplateBindingEvidence.SCHEMA_VERSION,
                "source", "plan_preflight"));

        assertThat(absent.present()).isFalse();
        assertThat(absent.invalidReason()).isNull();
        assertThat(malformed.present()).isTrue();
        assertThat(malformed.valid()).isFalse();
        assertThat(malformed.invalidReason()).contains("templateId is required");
    }

    @Test
    void preservesLegacyBindingsAndRoundTripsVersionSnapshot() {
        McpTemplateBindingEvidence legacy = McpTemplateBindingEvidence.from(Map.of(
            "schemaVersion", McpTemplateBindingEvidence.LEGACY_SCHEMA_VERSION,
            "source", "template_discovery",
            "templateId", "template-1",
            "executorTool", "mcp_template_execute"
        )).orElseThrow();
        McpTemplateBindingEvidence snapshotted = legacy.withSnapshot(
            "asset-7", "version-3", "sha256:abc");

        assertThat(legacy.schemaVersion()).isEqualTo(McpTemplateBindingEvidence.LEGACY_SCHEMA_VERSION);
        assertThat(snapshotted.toMap())
            .containsEntry("schemaVersion", McpTemplateBindingEvidence.SCHEMA_VERSION)
            .containsEntry("assetId", "asset-7")
            .containsEntry("templateVersion", "version-3")
            .containsEntry("contentHash", "sha256:abc");
        assertThat(McpTemplateBindingEvidence.from(snapshotted.toMap()))
            .contains(snapshotted);
    }
}
