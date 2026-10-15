package com.chatchat.mcpserver.python;

import com.chatchat.mcpserver.search.index.McpTemplateLuceneIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PythonAdminControllerOperationBindingTest {
    @Test
    void bindsOperationScopeAndFencingWithoutCompilerParameterMetadata() throws Exception {
        var managed = mock(PythonManagedExecutionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PythonAdminController(
                mock(PythonCapabilityService.class), mock(McpTemplateLuceneIndexService.class),
                mock(PythonDataFileService.class), managed)).build();
        String tenant = Base64.getUrlEncoder().withoutPadding().encodeToString("tenant-a".getBytes(StandardCharsets.UTF_8));
        String owner = Base64.getUrlEncoder().withoutPadding().encodeToString("user-a".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/python/runtime/operations/op-1")
                        .header("X-Tenant-Scope", tenant).header("X-Owner-Scope", owner)
                        .param("runId", "run-1").param("taskId", "task-1"))
                .andExpect(status().isOk());
        verify(managed).get("tenant-a", "user-a", "run-1", "task-1", "op-1");
        mvc.perform(post("/api/v1/python/runtime/operations/op-1/cancel")
                        .header("X-Tenant-Scope", tenant).header("X-Owner-Scope", owner)
                        .param("runId", "run-1").param("taskId", "task-1")
                        .param("fencingToken", "42"))
                .andExpect(status().isOk());
        verify(managed).cancel("tenant-a", "user-a", "run-1", "task-1", "op-1", 42L);
    }
}
