package com.chatchat.api.datascience;

import com.chatchat.agents.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonTemplateToolRegistryTest {
    @Test
    void publishedTemplatesAreRemovedFromLocalAgentToolSpace() {
        ToolRegistry tools = mock(ToolRegistry.class);
        PythonTemplateRepository repository = mock(PythonTemplateRepository.class);
        PythonTemplateEntity template = new PythonTemplateEntity();
        template.setToolName("python_legacy_direct_tool");
        when(repository.findByStatus("PUBLISHED")).thenReturn(List.of(template));
        PythonTemplateToolRegistry registry = new PythonTemplateToolRegistry(tools, repository);

        registry.registerPublished();
        registry.register(template);

        verify(tools, org.mockito.Mockito.times(2)).unregisterTool("python_legacy_direct_tool");
        verify(tools, never()).registerTool(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
