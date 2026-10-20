package com.chatchat.agents.runtime.plan.template;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TemplateAssetScopeTest {
    @Test void committedIdentityReplacesAllModelAssetPredicatesButPreservesTemplateIntent() {
        var scope = TemplateAssetScope.bind(Map.of(
            "assetName", "invented", "toolName", "invented-tool", "assetIds", List.of("wrong"), "target", "compactAlias",
            "targetType", "guessedType", "labels", List.of("guess"), "env", "wrong",
            "queryTerms", List.of("requested capability"), "businessGroup", "configured-group"),
            List.of(Map.of("assetId", "asset-1", "assetName", "Registry Name", "env", "TEST")));
        assertThat(scope).containsExactlyInAnyOrderEntriesOf(Map.of(
            "assetIds", List.of("asset-1"), "assetName", "Registry Name", "env", "TEST",
            "queryTerms", List.of("requested capability"), "businessGroup", "configured-group"));
    }

    @Test void multiAssetScopeCannotRetainTheFirstAssetsNameOrEnvironment() {
        var scope = TemplateAssetScope.bind(Map.of("target", "wrong", "assetName", "first", "env", "DEV"),
            List.of(Map.of("assetId", "first", "env", "DEV"), Map.of("assetId", "second", "env", "TEST")));
        assertThat(scope).containsExactlyInAnyOrderEntriesOf(Map.of("assetIds", List.of("first", "second")));
    }

    @Test void missingOrAmbiguousSelectionDoesNotWidenDiscovery() {
        assertThatThrownBy(() -> TemplateAssetScope.bind(Map.of(), List.of())).hasMessageContaining("ASSET_SELECTION_EMPTY");
        assertThatThrownBy(() -> TemplateAssetScope.bind(Map.of(),
            List.of(Map.of("assetName", "one"), Map.of("assetName", "two"))))
            .hasMessageContaining("ASSET_SELECTION_ID_REQUIRED");
    }

    @Test void legacySingleNameRemainsBoundWithoutInventingAnId() {
        assertThat(TemplateAssetScope.bind(Map.of("target", "guess"), List.of(Map.of("assetName", "legacy"))))
            .containsExactlyInAnyOrderEntriesOf(Map.of("assetName", "legacy"));
    }
}
