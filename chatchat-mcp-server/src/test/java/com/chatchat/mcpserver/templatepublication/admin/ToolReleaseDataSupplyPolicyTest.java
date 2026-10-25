package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.common.mcp.capability.McpDataSupplyPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolReleaseDataSupplyPolicyTest {
    @Test void ordinaryFixedPublicationDoesNotImplyPermissionToCollectEveryTemplate() throws Exception {
        var repository=mock(ToolReleaseRepository.class);var row=release("{\"type\":\"object\"}");
        when(repository.findByState("RUNNING")).thenReturn(List.of(row));
        assertThat(new ToolReleaseContractCatalog(repository,new ObjectMapper()).metadata("fixture_template_query"))
            .doesNotContainKey(McpDataSupplyPolicy.METADATA_KEY);
    }
    @Test void publishedPageSchemaAnnotationOptsInExplicitlyAndUnknownVersionsFailClosed() throws Exception {
        var json=new ObjectMapper();var repository=mock(ToolReleaseRepository.class);
        var row=release(json.writeValueAsString(Map.of("type","object","x-runtime-data-supply",McpDataSupplyPolicy.fixedBaseline())));
        when(repository.findByState("RUNNING")).thenReturn(List.of(row));var catalog=new ToolReleaseContractCatalog(repository,json);
        assertThat(catalog.metadata("fixture_template_query")).containsEntry(McpDataSupplyPolicy.METADATA_KEY,McpDataSupplyPolicy.fixedBaseline());
        row.setInputSchemaJson("{\"type\":\"object\",\"x-runtime-data-supply\":{\"contractVersion\":\"unknown\"}}");
        assertThat(catalog.metadata("fixture_template_query")).doesNotContainKey(McpDataSupplyPolicy.METADATA_KEY);
    }
    private ToolRelease release(String schema) {
        var row=new ToolRelease();row.setId("release");row.setLogicalId("logical");row.setKind("CONTROLLED_TEMPLATE");
        row.setReleaseNumber(1);row.setDefinitionJson("{\"domainCode\":\"fixture\"}");row.setInputSchemaJson(schema);return row;
    }
}
