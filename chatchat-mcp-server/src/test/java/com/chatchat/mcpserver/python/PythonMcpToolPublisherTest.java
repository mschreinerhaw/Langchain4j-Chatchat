package com.chatchat.mcpserver.python;

import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonMcpToolPublisherTest {
    @Test
    void refreshRemovesLegacyTemplateToolsAndPublishesOnlyThreeProtocolTools() {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of());
        @SuppressWarnings("unchecked") ObjectProvider<McpSyncServer> servers = mock(ObjectProvider.class);
        when(servers.getIfAvailable()).thenReturn(server);
        PythonTemplateAssetRepository templates = mock(PythonTemplateAssetRepository.class);
        PythonTemplate legacy = new PythonTemplate();
        legacy.setToolName("python_direct_template_1234");
        when(templates.findByStatus("PUBLISHED")).thenReturn(List.of(legacy));
        @SuppressWarnings("unchecked") ObjectProvider<PythonCapabilityService> services = mock(ObjectProvider.class);
        McpToolConcurrencyManager concurrency = mock(McpToolConcurrencyManager.class);
        when(concurrency.limitMeta(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Map.of());
        ObjectMapper objectMapper = new ObjectMapper();
        PythonMcpToolPublisher publisher = new PythonMcpToolPublisher(servers, templates,
            mock(PythonEnvironmentRepository.class), services, new PythonTemplateArgumentResolver(objectMapper),
            mock(PythonDataFileService.class), concurrency, objectMapper);

        publisher.refresh();

        verify(server).removeTool("python_direct_template_1234");
        verify(server).addTool(argThat(spec -> PythonMcpToolPublisher.ASSET_QUERY_TOOL.equals(spec.tool().name())));
        verify(server).addTool(argThat(spec -> PythonMcpToolPublisher.TEMPLATE_QUERY_TOOL.equals(spec.tool().name())));
        verify(server).addTool(argThat(spec -> PythonMcpToolPublisher.DATA_FILE_QUERY_TOOL.equals(spec.tool().name())));
        verify(server).addTool(argThat(spec -> PythonMcpToolPublisher.TEMPLATE_EXECUTE_TOOL.equals(spec.tool().name())));
        verify(server, times(4)).addTool(org.mockito.ArgumentMatchers.any());
        verify(server).notifyToolsListChanged();
    }

    @Test
    void assetDiscoveryDisambiguatesTwoRuntimeEnvironmentsByIntentAndExactFilter() {
        @SuppressWarnings("unchecked") ObjectProvider<McpSyncServer> servers = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<PythonCapabilityService> services = mock(ObjectProvider.class);
        PythonTemplateAssetRepository templates = mock(PythonTemplateAssetRepository.class);
        PythonEnvironmentRepository environments = mock(PythonEnvironmentRepository.class);
        PythonTemplate finance = template("template-finance", "asset-finance", "财务分析环境", "env-cpu",
            "财务日报分析", "汇总财务日报和部门金额");
        finance.setScriptFileName("log_analysis.py");
        PythonTemplate research = template("template-research", "asset-research", "研究计算环境", "env-gpu",
            "模型训练分析", "执行实验数据和模型指标分析");
        when(templates.findByTenantIdAndStatus("tenant-a", "PUBLISHED"))
            .thenReturn(List.of(finance, research));
        when(environments.findById("env-cpu")).thenReturn(java.util.Optional.of(environment("env-cpu", "CPU 通用环境")));
        when(environments.findById("env-gpu")).thenReturn(java.util.Optional.of(environment("env-gpu", "GPU 研究环境")));
        McpToolConcurrencyManager concurrency = mock(McpToolConcurrencyManager.class);
        ObjectMapper objectMapper = new ObjectMapper();
        PythonMcpToolPublisher publisher = new PythonMcpToolPublisher(servers, templates, environments,
            services, new PythonTemplateArgumentResolver(objectMapper), mock(PythonDataFileService.class), concurrency, objectMapper);

        Map<String, Object> ambiguous = publisher.discoverAssets(Map.of("tenantId", "tenant-a"));
        assertThat(ambiguous.get("returnedCount")).isEqualTo(2);
        assertThat(ambiguous.get("requiresClarification")).isEqualTo(true);
        assertThat(ambiguous.get("recommendedAssetId")).isEqualTo("");

        Map<String, Object> matched = publisher.discoverAssets(Map.of(
            "tenantId", "tenant-a", "query", "财务 日报"));
        assertThat(matched.get("returnedCount")).isEqualTo(1);
        assertThat(matched.get("requiresClarification")).isEqualTo(false);
        assertThat(matched.get("recommendedAssetId")).isEqualTo("asset-finance");
        @SuppressWarnings("unchecked") List<Map<String, Object>> matchedAssets =
            (List<Map<String, Object>>) matched.get("assets");
        assertThat(matchedAssets.get(0)).containsEntry("name", "财务分析环境")
            .containsEntry("environmentId", "env-cpu")
            .containsEntry("environmentName", "CPU 通用环境");

        Map<String, Object> filtered = publisher.discoverAssets(Map.of(
            "tenantId", "tenant-a", "environmentId", "env-gpu"));
        assertThat(filtered.get("returnedCount")).isEqualTo(1);
        assertThat(filtered.get("recommendedAssetId")).isEqualTo("asset-research");

        Map<String, Object> scriptMatched = publisher.discoverAssets(Map.of(
            "tenantId", "tenant-a", "query", "帮我运行log_analysis.py 并分析1786342932178.log日志文件"));
        assertThat(scriptMatched.get("returnedCount")).isEqualTo(1);
        assertThat(scriptMatched.get("recommendedAssetId")).isEqualTo("asset-finance");
    }

    @Test
    void dataFileDiscoveryUsesAuthenticatedOwnerAndReturnsOpaqueFileId() {
        @SuppressWarnings("unchecked") ObjectProvider<McpSyncServer> servers = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<PythonCapabilityService> services = mock(ObjectProvider.class);
        PythonDataFileService dataFiles = mock(PythonDataFileService.class);
        when(dataFiles.discover("tenant-a", "alice", "帮我运行log_analysis.py 并分析1786342932178.log日志文件", 20)).thenReturn(List.of(
            new PythonDataFileService.DataFileView("file-42", "1786342932178.log", 128L, 1000L)));
        ObjectMapper objectMapper = new ObjectMapper();
        PythonMcpToolPublisher publisher = new PythonMcpToolPublisher(servers,
            mock(PythonTemplateAssetRepository.class), mock(PythonEnvironmentRepository.class), services,
            new PythonTemplateArgumentResolver(objectMapper), dataFiles, mock(McpToolConcurrencyManager.class), objectMapper);

        Map<String, Object> result = publisher.discoverDataFiles(Map.of(
            "tenantId", "tenant-a", "username", "alice",
            "query", "帮我运行log_analysis.py 并分析1786342932178.log日志文件"));

        assertThat(result).containsEntry("recommendedFileId", "file-42")
            .containsEntry("requiresClarification", false)
            .containsEntry("userScoped", true);
        assertThat(String.valueOf(result)).doesNotContain("/data/input");
    }

    private PythonTemplate template(String id, String assetId, String assetName, String environmentId,
                                    String name, String scenario) {
        PythonTemplate template = new PythonTemplate();
        template.setId(id);
        template.setTenantId("tenant-a");
        template.setOwnerId("owner-a");
        template.setAssetId(assetId);
        template.setAssetName(assetName);
        template.setAssetDescription(assetName + "的业务描述");
        template.setEnvironmentId(environmentId);
        template.setTemplateName(name);
        template.setScenario(scenario);
        template.setDescription(scenario);
        template.setKeywords(scenario);
        template.setDomain(assetName);
        template.setStatus("PUBLISHED");
        return template;
    }

    private PythonEnvironment environment(String id, String name) {
        PythonEnvironment environment = new PythonEnvironment();
        environment.setId(id);
        environment.setName(name);
        environment.setDescription(name + "描述");
        environment.setPythonVersion("3.11");
        environment.setRequirementsJson("[]");
        return environment;
    }
}
