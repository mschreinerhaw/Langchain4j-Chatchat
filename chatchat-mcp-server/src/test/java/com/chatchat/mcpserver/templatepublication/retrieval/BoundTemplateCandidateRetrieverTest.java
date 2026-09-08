package com.chatchat.mcpserver.templatepublication.retrieval;

import com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BoundTemplateCandidateRetrieverTest {

    @Test
    void ranksAndPagesOnlyInsideLargeBoundUniverse() {
        List<TemplateAssetCatalogService.TemplateAsset> assets = new ArrayList<>();
        Set<String> allowed = new LinkedHashSet<>();
        for (int index = 0; index < 10_000; index++) {
            String id = "BOUND_" + index;
            allowed.add(id);
            assets.add(asset(id, index == 9_999
                ? "customer transaction profit and loss analysis"
                : "unrelated capability " + index));
        }
        assets.add(asset("UNBOUND_BEST_MATCH", "customer transaction profit and loss analysis"));
        BoundTemplateCandidateRetriever retriever = new BoundTemplateCandidateRetriever();
        Map<String, Object> request = Map.of(
            "filters", Map.of("intent", "customer transaction profit and loss analysis"));

        BoundTemplateCandidateRetriever.Recall first =
            retriever.recall(assets, allowed, request, 10, "policy-v1");

        assertThat(first.candidateUniverseCount()).isEqualTo(10_000);
        assertThat(first.templates()).hasSize(10);
        assertThat(first.templates().get(0).templateId()).isEqualTo("BOUND_9999");
        assertThat(first.templates()).extracting(TemplateAssetCatalogService.TemplateAsset::templateId)
            .doesNotContain("UNBOUND_BEST_MATCH");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();

        BoundTemplateCandidateRetriever.Recall second = retriever.recall(
            assets, allowed,
            Map.of("filters", request.get("filters"), "cursor", first.nextCursor()),
            10, "policy-v1");

        assertThat(second.offset()).isEqualTo(10);
        assertThat(second.templates()).hasSize(10);
        assertThat(second.templates()).doesNotContainAnyElementsOf(first.templates());
    }

    @Test
    void rejectsCursorWhenBindingOrQueryChanges() {
        BoundTemplateCandidateRetriever retriever = new BoundTemplateCandidateRetriever();
        List<TemplateAssetCatalogService.TemplateAsset> assets = List.of(
            asset("A", "asset"), asset("B", "balance"));
        BoundTemplateCandidateRetriever.Recall first = retriever.recall(
            assets, Set.of("A", "B"), Map.of("query", "asset"), 1, "policy-v1");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> retriever.recall(
                assets, Set.of("A", "B"),
                Map.of("query", "changed", "cursor", first.nextCursor()), 1, "policy-v1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cursor");
    }

    private TemplateAssetCatalogService.TemplateAsset asset(String id, String description) {
        return new TemplateAssetCatalogService.TemplateAsset(
            "api_service:" + id, "api_service", id, id, description,
            "", "", "", Map.of("type", "object"));
    }
}
