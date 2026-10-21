package com.chatchat.api.datascience.python;

import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
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
    @Mock McpPythonControlPlanePort mcp;
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


    @Test void deletingFolderPreservesScriptsAndPromotesChildren(){
        var parent=new PythonScriptFolderEntity();parent.setId("parent");parent.setName("Parent");
        var removed=new PythonScriptFolderEntity();removed.setId("removed");removed.setName("Removed");removed.setParentId("parent");
        var child=new PythonScriptFolderEntity();child.setId("child");child.setName("Child");child.setParentId("removed");
        var script=script("script");script.setFolderId("removed");
        when(folderRepository.findByIdAndTenantIdAndOwnerId("removed","tenant-1","user-1")).thenReturn(Optional.of(removed));
        when(folderRepository.findByIdAndTenantIdAndOwnerId("parent","tenant-1","user-1")).thenReturn(Optional.of(parent));
        when(folderRepository.findByTenantIdAndOwnerIdOrderBySortOrderAscNameAsc("tenant-1","user-1")).thenReturn(List.of(parent,removed,child));
        when(scriptRepository.findByTenantIdAndOwnerIdOrderByUpdatedAtDesc("tenant-1","user-1")).thenReturn(List.of(script));
        service.deleteFolder("tenant-1","user-1","removed");
        assertThat(child.getParentId()).isEqualTo("parent");assertThat(script.getFolderId()).isEqualTo("parent");
        verify(folderRepository).delete(removed);verify(scriptRepository).save(script);verify(versionRepository,never()).deleteByScriptId("script");
    }
    @Test void deletingFolderRejectsSiblingNameCollisionBeforeMovingAnyContent(){
        var removed=new PythonScriptFolderEntity();removed.setId("removed");removed.setName("Removed");
        var child=new PythonScriptFolderEntity();child.setId("child");child.setName("Child");child.setParentId("removed");
        var sibling=new PythonScriptFolderEntity();sibling.setId("sibling");sibling.setName("CHILD");
        when(folderRepository.findByIdAndTenantIdAndOwnerId("removed","tenant-1","user-1")).thenReturn(Optional.of(removed));
        when(folderRepository.findByTenantIdAndOwnerIdOrderBySortOrderAscNameAsc("tenant-1","user-1")).thenReturn(List.of(removed,child,sibling));
        assertThatThrownBy(()->service.deleteFolder("tenant-1","user-1","removed")).isInstanceOf(IllegalArgumentException.class);
        assertThat(child.getParentId()).isEqualTo("removed");verify(folderRepository,never()).delete(removed);
    }
    @Test void folderCannotBeMovedBelowItsOwnChild(){
        var folder=new PythonScriptFolderEntity();folder.setId("parent");folder.setName("Parent");
        var child=new PythonScriptFolderEntity();child.setId("child");child.setName("Child");child.setParentId("parent");
        when(folderRepository.findByIdAndTenantIdAndOwnerId("parent","tenant-1","user-1")).thenReturn(Optional.of(folder));
        when(folderRepository.findByIdAndTenantIdAndOwnerId("child","tenant-1","user-1")).thenReturn(Optional.of(child));
        assertThatThrownBy(()->service.saveFolder("tenant-1","user-1",new PythonDataScienceService.FolderRequest("parent","child","Parent",0))).isInstanceOf(IllegalArgumentException.class);
        verify(folderRepository,never()).save(folder);
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
