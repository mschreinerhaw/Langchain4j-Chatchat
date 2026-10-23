package com.chatchat.mcpserver.templatepublication.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

@Component @RequiredArgsConstructor
public class ToolCategoryMigration {
    private final ToolCategoryService service;
    @EventListener(ApplicationReadyEvent.class) @Order(210)
    public void initialize() { service.initialize(); }
}
