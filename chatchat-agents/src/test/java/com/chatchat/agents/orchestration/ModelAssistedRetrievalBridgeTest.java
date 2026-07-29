package com.chatchat.agents.orchestration;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelAssistedRetrievalBridgeTest {

    @Test
    @SuppressWarnings("unchecked")
    void expandsDocumentQueryButPreservesPermissionAndDocumentScope() {
        ToolRegistry registry = registry("document_search", contract(
            "QUERY_EXPANSION",
            List.of("query"),
            List.of("query"),
            Map.of("query", "append_text")
        ));
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {
              "profile":{"intent":"retrieve policy definition","terms":["客户身份识别","KYC"]},
              "arguments":{"query":"客户身份识别 KYC policy 2026"}
            }
            """);
        ModelAssistedRetrievalBridge bridge =
            new ModelAssistedRetrievalBridge(registry, new ObjectMapper());
        Map<String, Object> filters = Map.of("company", "Apex", "fileType", "pdf");

        Map<String, Object> result = bridge.enrich(model, "document_search", Map.of(
            "query", "《客户管理制度》2026版中的身份识别要求",
            "fileIds", List.of("doc-1"),
            "tenantId", "tenant-1",
            "userId", "user-1",
            "roles", List.of("analyst"),
            "filters", filters
        ));

        assertThat(String.valueOf(result.get("query")))
            .contains("《客户管理制度》2026版中的身份识别要求", "客户身份识别", "KYC");
        assertThat(result)
            .containsEntry("fileIds", List.of("doc-1"))
            .containsEntry("tenantId", "tenant-1")
            .containsEntry("userId", "user-1")
            .containsEntry("roles", List.of("analyst"))
            .containsEntry("filters", filters);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsBilingualTemplateProfileWithoutChangingRoutingFields() {
        ToolRegistry registry = registry("database_ops_template_search", contract(
            "BILINGUAL_TEMPLATE_PROFILE",
            List.of("filters.intent"),
            List.of("bilingualIntent", "intentZh", "intentEn"),
            Map.of("bilingualIntent", "merge_array")
        ));
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {
              "profile":{"intent":"database health inspection","terms":["数据库健康检查"]},
              "arguments":{
                "bilingualIntent":["数据库健康检查","database health inspection"],
                "intentZh":"数据库健康检查",
                "intentEn":"database health inspection",
                "finalDecision":"host"
              }
            }
            """);
        ModelAssistedRetrievalBridge bridge =
            new ModelAssistedRetrievalBridge(registry, new ObjectMapper());
        Map<String, Object> result = bridge.enrich(model, "database_ops_template_search", Map.of(
            "filters", Map.of("intent", "检查数据库健康状态", "env", "PROD"),
            "finalDecision", "database",
            "trace", Map.of("source", "runtime")
        ));

        assertThat((List<String>) result.get("bilingualIntent"))
            .containsExactly("数据库健康检查", "database health inspection");
        assertThat(result)
            .containsEntry("intentZh", "数据库健康检查")
            .containsEntry("intentEn", "database health inspection")
            .containsEntry("finalDecision", "database");
        assertThat((Map<String, Object>) result.get("filters"))
            .containsEntry("env", "PROD")
            .containsEntry("intent", "检查数据库健康状态");
    }

    @Test
    void toolsWithoutPublishedContractDoNotInvokeModel() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata("database_query")).thenReturn(ToolMetadata.builder()
            .id("database_query")
            .metadata(Map.of())
            .build());
        ChatModel model = mock(ChatModel.class);
        ModelAssistedRetrievalBridge bridge =
            new ModelAssistedRetrievalBridge(registry, new ObjectMapper());
        Map<String, Object> input = Map.of("sql", "select 1");

        assertThat(bridge.enrich(model, "database_query", input))
            .containsExactlyInAnyOrderEntriesOf(input);
        verifyNoInteractions(model);
    }

    @Test
    @SuppressWarnings("unchecked")
    void qualityGateKeepsOriginalValuesForOneFallbackExecution() {
        Map<String, Object> bridgeContract = new java.util.LinkedHashMap<>(contract(
            "QUERY_EXPANSION",
            List.of("query"),
            List.of("query"),
            Map.of("query", "append_text")
        ));
        bridgeContract.put("qualityGate", Map.of(
            "enabled", true,
            "minimumResultCount", 1,
            "countPaths", List.of("results")
        ));
        ToolRegistry registry = registry("document_search", bridgeContract);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {"arguments":{"query":"internal policy expanded"}}
            """);
        ModelAssistedRetrievalBridge bridge =
            new ModelAssistedRetrievalBridge(registry, new ObjectMapper());

        ModelAssistedRetrievalBridge.EnrichmentResult result = bridge.enrichWithGate(
            model,
            "document_search",
            Map.of("query", "internal", "tenantId", "tenant-1")
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.arguments())
            .containsEntry("query", "internal policy expanded")
            .containsEntry("tenantId", "tenant-1");
        assertThat((Map<String, Object>) result.qualityGate().get("originalValues"))
            .containsEntry("query", "internal");
        assertThat(result.argumentsWithGateMarker())
            .containsKey(ModelAssistedRetrievalBridge.RUNTIME_GATE_KEY);
    }

    private ToolRegistry registry(String toolName, Map<String, Object> contract) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .metadata(Map.of("mcpToolMeta", Map.of(
                ModelAssistedRetrievalBridge.META_KEY, contract
            )))
            .build());
        return registry;
    }

    private Map<String, Object> contract(String mode,
                                         List<String> contextPaths,
                                         List<String> allowedPaths,
                                         Map<String, Object> mergeModes) {
        return Map.of(
            "contractVersion", ModelAssistedRetrievalBridge.CONTRACT_VERSION,
            "mode", mode,
            "contextPaths", contextPaths,
            "allowedArgumentPaths", allowedPaths,
            "mergeModes", mergeModes,
            "guidance", "test retrieval profile"
        );
    }
}
