package com.chatchat.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconciles the MCP directory already maintained by tools/list and tool-change notifications. */
@Component
@Slf4j
public class CapabilityCatalogSynchronizer {
    private final CapabilityAccessService capabilities;
    public CapabilityCatalogSynchronizer(CapabilityAccessService capabilities) { this.capabilities = capabilities; }
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { synchronize(); }
    @Scheduled(fixedDelayString = "${chatchat.capabilities.sync-interval-ms:60000}",
        initialDelayString = "${chatchat.capabilities.sync-initial-delay-ms:60000}")
    public void synchronize() {
        try { capabilities.synchronizeCatalog(); }
        catch (RuntimeException failure) {
            log.warn("Capability catalog snapshot synchronization deferred: {}", failure.getClass().getSimpleName());
        }
    }
}
