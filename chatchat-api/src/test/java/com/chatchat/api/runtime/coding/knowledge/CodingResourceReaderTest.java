package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.api.controller.mcp.McpControlPlaneClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingResourceReaderTest {
    final ObjectMapper json=new ObjectMapper();
    final McpControlPlaneClient mcp=mock(McpControlPlaneClient.class);
    final CodingResourceReader reader=new CodingResourceReader(mcp,json);
    @Test void batchUsesFixedAuthenticatedOriginAndRejectsTruncatedOrMisalignedResponses() {
        var reads=List.of(new CodingResourceReader.Read("market_get_dataset",json.valueToTree(Map.of("dataset","quotes"))));
        when(mcp.request(eq("POST"),eq("/api/v1/resource-exploration/read-batch"),any())).thenReturn(json.valueToTree(List.of(Map.of("dataset_code","quotes","authority","UNTRUSTED_SOURCE_DATA"))));
        assertThat(reader.readBatch(reads)).singleElement().satisfies(source->assertThat(source).containsEntry("dataset_code","quotes").doesNotContainKey("authority"));
        when(mcp.request(anyString(),anyString(),any())).thenReturn(json.createArrayNode());
        assertThatThrownBy(()->reader.readBatch(reads)).isInstanceOf(IllegalStateException.class);
    }
    @Test void batchesCannotAddSearchIdentityOrUnboundedOperations() {
        assertThatThrownBy(()->reader.readBatch(List.of(new CodingResourceReader.Read("market_search",json.createObjectNode())))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->reader.readBatch(Collections.nCopies(17,new CodingResourceReader.Read("market_get_dataset",json.createObjectNode())))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mcp);
    }
}
