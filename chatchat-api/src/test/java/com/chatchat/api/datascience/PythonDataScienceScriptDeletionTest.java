package com.chatchat.api.datascience;

import com.chatchat.integration.mcp.service.McpPythonControlPlaneClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PythonDataScienceScriptDeletionTest {
    @Mock PythonAssetRepository assetRepository;
    @Mock PythonScriptRepository scriptRepository;
    @Mock PythonScriptFolderRepository folderRepository;
    @Mock PythonScriptVersionRepository versionRepository;
    @Mock PythonTemplateRepository templateRepository;
    @Mock PythonExecutionRepository executionRepository;
    @Mock McpPythonControlPlaneClient mcp;
    @Mock PythonTemplateIndexService indexService;
    @Mock PythonTemplateToolRegistry registry;
    @Mock ObjectMapper objectMapper;
    @Mock PythonDataFileRepository dataFileRepository;
    @Mock PythonDataScienceProperties properties;
    @Mock PythonSystemExampleCatalog exampleCatalog;
    @InjectMocks PythonDataScienceService service;

    @Test
    void deletesOwnedUnpublishedScriptAndItsVersions() {
        PythonScriptEntity script = script("script-1");
        when(scriptRepository.findByIdAndTenantIdAndOwnerId("script-1", "tenant-1", "user-1"))
                .thenReturn(Optional.of(script));
        when(templateRepository.findByScriptIdOrderByPublishedAtDesc("script-1")).thenReturn(List.of());

        service.deleteScript("tenant-1", "user-1", "script-1");

        verify(versionRepository).deleteByScriptId("script-1");
        verify(scriptRepository).delete(script);
    }

    @Test
    void offlinesPublishedTemplateBeforeDeletingScript() {
        PythonScriptEntity script = script("script-1");
        PythonTemplateEntity template = template("template-1", "script-1", "PUBLISHED");
        when(scriptRepository.findByIdAndTenantIdAndOwnerId("script-1", "tenant-1", "user-1"))
                .thenReturn(Optional.of(script));
        when(templateRepository.findByScriptIdOrderByPublishedAtDesc("script-1"))
                .thenReturn(List.of(template));

        service.deleteScript("tenant-1", "user-1", "script-1");

        verify(mcp).setTemplateEnabled("template-1", false);
        verify(indexService).remove("template-1");
        verify(registry).unregister(template);
        verify(versionRepository).deleteByScriptId("script-1");
        verify(scriptRepository).delete(script);
        assertThat(template.getStatus()).isEqualTo("DELETED");
        assertThat(template.getRuntimeStatus()).isEqualTo("DISABLED");
        assertThat(template.getIndexStatus()).isEqualTo("REMOVED");
    }

    @Test
    void keepsScriptWhenMcpTemplateCannotBeOfflined() {
        PythonScriptEntity script = script("script-1");
        PythonTemplateEntity template = template("template-1", "script-1", "PUBLISHED");
        when(scriptRepository.findByIdAndTenantIdAndOwnerId("script-1", "tenant-1", "user-1"))
                .thenReturn(Optional.of(script));
        when(templateRepository.findByScriptIdOrderByPublishedAtDesc("script-1"))
                .thenReturn(List.of(template));
        doThrow(new IllegalStateException("MCP unavailable"))
                .when(mcp).setTemplateEnabled("template-1", false);

        assertThatThrownBy(() -> service.deleteScript("tenant-1", "user-1", "script-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("脚本未删除");

        verify(versionRepository, never()).deleteByScriptId("script-1");
        verify(scriptRepository, never()).delete(script);
        assertThat(template.getStatus()).isEqualTo("PUBLISHED");
    }

    private PythonScriptEntity script(String id) {
        PythonScriptEntity script = new PythonScriptEntity();
        script.setId(id);
        script.setTenantId("tenant-1");
        script.setOwnerId("user-1");
        return script;
    }

    private PythonTemplateEntity template(String id, String scriptId, String status) {
        PythonTemplateEntity template = new PythonTemplateEntity();
        template.setId(id);
        template.setScriptId(scriptId);
        template.setStatus(status);
        template.setRuntimeStatus("READY");
        template.setIndexStatus("LOCAL_ONLY");
        template.setMcpSyncStatus("SYNCED");
        template.setToolName("python_log_analysis");
        return template;
    }
}
