package com.chatchat.mcpserver.templatepublication.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolReleaseContractCatalogTest {
    @Test void strictBusinessSchemaExcludesRuntimeEnvelopeButStillRejectsUnknownBusinessFields() {
        var repository=mock(ToolReleaseRepository.class);
        var release=new ToolRelease();
        release.setKind("CONTROLLED_TEMPLATE");
        release.setDefinitionJson("{\"domainCode\":\"example\"}");
        release.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}},\"additionalProperties\":false}");
        when(repository.findByState("RUNNING")).thenReturn(List.of(release));
        var catalog=new ToolReleaseContractCatalog(repository,new ObjectMapper());
        var input=new LinkedHashMap<String,Object>(Map.of("limit",5,"requestId","request", "conversationId","conversation",
            "agentId","agent","callerProof",Map.of(),"mcpContext",Map.of(),"_templateQueryChildToolName","example_template_query"));
        assertThatCode(()->catalog.validateInput("example_template_query",input)).doesNotThrowAnyException();
        assertThat(input).containsKeys("requestId","agentId");
        input.put("unexpectedBusinessArgument",true);
        assertThatThrownBy(()->catalog.validateInput("example_template_query",input)).hasMessageContaining("Schema validation failed");
    }
}
