package com.chatchat.api.datascience;

import com.chatchat.agents.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Removes legacy per-template Agent tools. Python execution is MCP protocol governed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PythonTemplateToolRegistry {
    private final ToolRegistry toolRegistry;
    private final PythonTemplateRepository repository;

    @EventListener(ApplicationReadyEvent.class)
    public void registerPublished() {
        repository.findByStatus("PUBLISHED").forEach(this::unregister);
        log.info("Python per-template Agent tools are disabled; use the MCP asset/template/execute protocol");
    }

    public void register(PythonTemplateEntity template) {
        unregister(template);
    }

    public void unregister(PythonTemplateEntity template) {
        if (template != null && template.getToolName() != null) toolRegistry.unregisterTool(template.getToolName());
    }
}
