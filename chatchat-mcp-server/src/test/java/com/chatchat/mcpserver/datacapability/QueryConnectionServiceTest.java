package com.chatchat.mcpserver.datacapability;

import com.chatchat.mcpserver.datacapability.connection.*;
import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.ops.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryConnectionServiceTest {
    private final HttpEndpointConfigService assets = mock(HttpEndpointConfigService.class);
    private final QueryConnectionService service = new QueryConnectionService(assets);

    private HttpEndpointConfig asset(String id, String category) {
        var asset = new HttpEndpointConfig();
        asset.setId(id); asset.setName(category); asset.setCategory(category); asset.setEnabled(true);
        asset.setMethod("POST"); asset.setUrlTemplate("https://datasource.example.com");
        asset.setHeadersJson("{\"Authorization\":\"Bearer secret\"}"); return asset;
    }

    @Test void selectsExistingAssetsByTypeWithoutExposingConnectionOrAuthenticationDetails() throws Exception {
        var graph = asset("existing-graph-asset", "graph_database");
        var search = asset("existing-search-asset", "search_engine");
        when(assets.listAll()).thenReturn(List.of(graph, search, asset("business-api", "business_api")));
        assertThat(service.list(CapabilityType.GRAPH)).extracting(QueryConnectionService.AssetReference::id)
            .containsExactly("existing-graph-asset");
        assertThat(service.list(null)).hasSize(2);
        String payload = new ObjectMapper().writeValueAsString(service.list(null));
        assertThat(payload).doesNotContain("Authorization", "secret", "datasource.example.com");
    }

    @Test void resolvesCurrentAssetStateOnEveryInvocationAndRejectsWrongTypes() {
        var graph = asset("graph", "graph_database"); when(assets.getById("graph")).thenReturn(graph);
        assertThat(service.get("graph", CapabilityType.GRAPH, true)).isSameAs(graph);
        graph.setEnabled(false);
        assertThatThrownBy(() -> service.get("graph", CapabilityType.GRAPH, true)).hasMessageContaining("disabled");
        assertThat(service.get("graph", CapabilityType.GRAPH, false)).isSameAs(graph);
        assertThatThrownBy(() -> service.get("graph", CapabilityType.UNSTRUCTURED, false)).hasMessageContaining("type mismatch");
        verify(assets, times(4)).getById("graph");
    }

    @Test void rejectsQueryTemplatesAndNonPostAssetsAsDatasourceBases() {
        var graph = asset("graph", "graph_database"); when(assets.getById("graph")).thenReturn(graph);
        graph.setMethod("GET");
        assertThatThrownBy(() -> service.get("graph", CapabilityType.GRAPH, false)).hasMessageContaining("POST");
        graph.setMethod("POST"); graph.setUrlTemplate("https://example.com?token=secret");
        assertThatThrownBy(() -> service.get("graph", CapabilityType.GRAPH, false)).hasMessageContaining("base address");
    }

    @Test void centralAssetDeletionGuardPreventsDanglingReferencesAndSeparatesAssetFamilies() {
        var repository = mock(CapabilityRepository.class);
        var definition = new CapabilityEntity(); definition.setCode("company_graph"); definition.setType(CapabilityType.GRAPH);
        definition.setDefinitionJson("{\"connectionId\":\"asset-1\"}");
        when(repository.findAll()).thenReturn(List.of(definition));
        var guard = new CapabilityAssetReferenceGuard(repository, new ObjectMapper());
        assertThatThrownBy(() -> guard.assertUnused("asset-1", false)).hasMessageContaining("company_graph");
        assertThatCode(() -> guard.assertUnused("asset-1", true)).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertUnused("other-asset", false)).doesNotThrowAnyException();
    }
}
