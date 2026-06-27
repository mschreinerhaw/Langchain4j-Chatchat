package com.chatchat.mcpserver.ops;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TemplateDiscoveryMcpToolPublisherTest {

    @Test
    void templateQueryToolIsReadOnlyAndDoesNotReturnRawExecutionSpec() throws Exception {
        TemplateDiscoveryMcpToolPublisher publisher = new TemplateDiscoveryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(CommandTemplateDiscoveryService.class)
        );
        Method templateQueryTool = TemplateDiscoveryMcpToolPublisher.class.getDeclaredMethod("templateQueryTool");
        templateQueryTool.setAccessible(true);

        McpServerFeatures.SyncToolSpecification spec =
            (McpServerFeatures.SyncToolSpecification) templateQueryTool.invoke(publisher);
        McpSchema.Tool tool = spec.tool();
        Map<?, ?> meta = tool.meta();
        Map<?, ?> confirmation = (Map<?, ?>) meta.get("confirmation");
        Map<?, ?> languageSupport = (Map<?, ?>) meta.get("languageSupport");

        assertThat(tool.name()).isEqualTo(TemplateDiscoveryMcpToolPublisher.TOOL_NAME);
        assertThat(tool.description()).contains("bilingual Chinese and English");
        assertThat(meta.get("runtimeAction")).isEqualTo("read_only");
        assertThat(meta.get("readOnly")).isEqualTo(true);
        assertThat(meta.get("riskLevel")).isEqualTo("low");
        assertThat(meta.get("rawExecutionSpecReturned")).isEqualTo(false);
        assertThat(languageSupport.get("mode")).isEqualTo("bilingual");
        assertThat(languageSupport.get("modelMustGenerateBilingualRetrieval")).isEqualTo(true);
        assertThat(languageSupport.get("languages").toString()).contains("zh", "en");
        assertThat(languageSupport.get("bilingualQueryFields").toString()).contains("bilingualIntent", "intentZh", "intentEn");
        assertThat(tool.inputSchema().toString()).contains("bilingualIntent", "bilingualQuery", "intentZh", "intentEn", "language", "queryLanguage");
        assertThat(confirmation.get("default")).isEqualTo("auto_execute");
        assertThat(confirmation.get("allow_user_override")).isEqualTo(false);
    }
}
