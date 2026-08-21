package com.chatchat.api.datascience;

import com.chatchat.integration.mcp.service.McpPythonControlPlaneClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void deletesOwnedScriptAndItsVersions() {
        PythonScriptEntity script = script("script-1");
        when(scriptRepository.findByIdAndTenantIdAndOwnerId("script-1", "tenant-1", "user-1"))
                .thenReturn(Optional.of(script));
        when(templateRepository.existsByScriptId("script-1")).thenReturn(false);

        service.deleteScript("tenant-1", "user-1", "script-1");

        verify(versionRepository).deleteByScriptId("script-1");
        verify(scriptRepository).delete(script);
    }

    @Test
    void keepsScriptReferencedByPublishedTemplate() {
        PythonScriptEntity script = script("script-1");
        when(scriptRepository.findByIdAndTenantIdAndOwnerId("script-1", "tenant-1", "user-1"))
                .thenReturn(Optional.of(script));
        when(templateRepository.existsByScriptId("script-1")).thenReturn(true);

        assertThatThrownBy(() -> service.deleteScript("tenant-1", "user-1", "script-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("脚本已发布为模板，当前不能删除，以免破坏 Agent Runtime");
    }

    private PythonScriptEntity script(String id) {
        PythonScriptEntity script = new PythonScriptEntity();
        script.setId(id);
        script.setTenantId("tenant-1");
        script.setOwnerId("user-1");
        return script;
    }
}
