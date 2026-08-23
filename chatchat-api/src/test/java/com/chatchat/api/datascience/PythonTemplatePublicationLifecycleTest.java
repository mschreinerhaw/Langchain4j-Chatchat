package com.chatchat.api.datascience;

import com.chatchat.integration.mcp.service.McpPythonControlPlaneClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PythonTemplatePublicationLifecycleTest {
    @Mock PythonAssetRepository assetRepository;
    @Mock PythonScriptRepository scriptRepository;
    @Mock PythonScriptFolderRepository folderRepository;
    @Mock PythonScriptVersionRepository versionRepository;
    @Mock PythonTemplateRepository templateRepository;
    @Mock PythonExecutionRepository executionRepository;
    @Mock McpPythonControlPlaneClient mcp;
    @Mock PythonTemplateIndexService indexService;
    @Mock PythonTemplateToolRegistry registry;
    @Mock PythonDataFileRepository dataFileRepository;
    @Mock PythonDataScienceProperties properties;
    @Mock PythonSystemExampleCatalog exampleCatalog;

    private PythonDataScienceService service;

    @BeforeEach
    void setUp() {
        service = new PythonDataScienceService(assetRepository, scriptRepository, folderRepository,
                versionRepository, templateRepository, executionRepository, mcp, indexService, registry,
                new ObjectMapper(), dataFileRepository, properties, exampleCatalog);
    }

    @Test
    void republishesScriptByUpdatingStableTemplateIdentity() throws Exception {
        PythonScriptEntity script = script("print('version 2')", 2);
        PythonAssetEntity asset = asset();
        PythonTemplateEntity existing = publishedTemplate();
        when(scriptRepository.findByIdAndTenantIdAndOwnerId("script-1", "tenant-1", "alice"))
                .thenReturn(Optional.of(script));
        when(assetRepository.findByIdAndTenantIdAndOwnerId("asset-1", "tenant-1", "alice"))
                .thenReturn(Optional.of(asset));
        when(templateRepository.findFirstByScriptIdOrderByPublishedAtDesc("script-1"))
                .thenReturn(Optional.of(existing));
        when(templateRepository.saveAndFlush(any(PythonTemplateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(templateRepository.save(any(PythonTemplateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        String sourceHash = sha256(script.getSourceCode());
        when(mcp.synchronizeTemplate(eq("template-1"), any()))
                .thenReturn(new McpPythonControlPlaneClient.SyncResult(
                        "template-1", "PUBLISHED", "python_log_stable", "env-1", sourceHash));
        when(indexService.index(any(PythonTemplateEntity.class)))
                .thenReturn(new PythonTemplateIndexService.IndexResult(true, "LOCAL_ONLY", ""));

        PythonTemplateEntity result = service.publish("tenant-1", "alice", "script-1",
                new PythonDataScienceService.PublishRequest("Updated log analysis", "logs", "new description",
                        "log,error", "operations", "2.0.0", "{}", "{}"));

        assertThat(result.getId()).isEqualTo("template-1");
        assertThat(result.getToolName()).isEqualTo("python_log_stable");
        assertThat(result.getScriptVersion()).isEqualTo(2);
        assertThat(result.getSourceSnapshot()).isEqualTo("print('version 2')");
        assertThat(result.getStatus()).isEqualTo("PUBLISHED");
        assertThat(result.getMcpSyncStatus()).isEqualTo("SYNCED");
        ArgumentCaptor<McpPythonControlPlaneClient.TemplatePayload> payload =
                ArgumentCaptor.forClass(McpPythonControlPlaneClient.TemplatePayload.class);
        verify(mcp).synchronizeTemplate(eq("template-1"), payload.capture());
        assertThat(payload.getValue().source()).isEqualTo("print('version 2')");
        assertThat(payload.getValue().toolName()).isEqualTo("python_log_stable");
        verify(registry).register(result);
    }

    @Test
    void disablesNewRemoteTemplateWhenInitialPublicationFails() {
        PythonScriptEntity script = script("print('first version')", 1);
        PythonAssetEntity asset = asset();
        when(scriptRepository.findByIdAndTenantIdAndOwnerId("script-1", "tenant-1", "alice"))
                .thenReturn(Optional.of(script));
        when(assetRepository.findByIdAndTenantIdAndOwnerId("asset-1", "tenant-1", "alice"))
                .thenReturn(Optional.of(asset));
        when(templateRepository.findFirstByScriptIdOrderByPublishedAtDesc("script-1"))
                .thenReturn(Optional.empty());
        when(templateRepository.saveAndFlush(any(PythonTemplateEntity.class))).thenAnswer(invocation -> {
            PythonTemplateEntity template = invocation.getArgument(0);
            template.setId("new-template");
            return template;
        });
        when(mcp.synchronizeTemplate(eq("new-template"), any()))
                .thenThrow(new IllegalStateException("connection lost after request"));

        assertThatThrownBy(() -> service.publish("tenant-1", "alice", "script-1",
                new PythonDataScienceService.PublishRequest("Log analysis", "logs", "description",
                        "log", "operations", "1.0.0", "{}", "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection lost after request");

        verify(mcp).setTemplateEnabled("new-template", false);
    }

    private PythonScriptEntity script(String source, int version) {
        PythonScriptEntity script = new PythonScriptEntity();
        script.setId("script-1");
        script.setTenantId("tenant-1");
        script.setOwnerId("alice");
        script.setAssetId("asset-1");
        script.setFileName("log_analysis.py");
        script.setSourceCode(source);
        script.setCurrentVersion(version);
        script.setStatus("TESTED");
        script.setLastTestSucceeded(true);
        return script;
    }

    private PythonAssetEntity asset() {
        PythonAssetEntity asset = new PythonAssetEntity();
        asset.setId("asset-1");
        asset.setTenantId("tenant-1");
        asset.setOwnerId("alice");
        asset.setName("Python environment");
        asset.setDescription("isolated runtime");
        asset.setMcpEnvironmentId("env-1");
        asset.setStatus("READY");
        return asset;
    }

    private PythonTemplateEntity publishedTemplate() {
        PythonTemplateEntity template = new PythonTemplateEntity();
        template.setId("template-1");
        template.setTenantId("tenant-1");
        template.setOwnerId("alice");
        template.setAssetId("asset-1");
        template.setScriptId("script-1");
        template.setScriptVersion(1);
        template.setTemplateName("Log analysis");
        template.setToolName("python_log_stable");
        template.setVersion("1.0.0");
        template.setScenario("logs");
        template.setDescription("old description");
        template.setKeywords("log");
        template.setDomain("operations");
        template.setInputSchemaJson("{}");
        template.setOutputSchemaJson("{}");
        template.setSourceSnapshot("print('version 1')");
        template.setSearchText("old");
        template.setStatus("PUBLISHED");
        template.setIndexStatus("LOCAL_ONLY");
        template.setRuntimeStatus("READY");
        template.setMcpSyncStatus("SYNCED");
        template.setPublishedAt(Instant.now().minusSeconds(60));
        template.setUpdatedAt(Instant.now().minusSeconds(60));
        return template;
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
