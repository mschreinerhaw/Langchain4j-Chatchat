package com.chatchat.mcpserver.license;

import com.chatchat.license.LicenseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Slf4j
public class LicenseRuntimeMonitor {
    private final McpLicenseService licenseService;
    private final AtomicReference<String> previousStatus = new AtomicReference<>();

    @Scheduled(initialDelayString = "${chatchat.license.status-check-interval-ms:60000}",
        fixedDelayString = "${chatchat.license.status-check-interval-ms:60000}")
    public void checkStatusTransition() {
        LicenseStatus current = licenseService.status();
        String previous = previousStatus.getAndSet(current.status());
        if (current.status().equals(previous)) return;
        if (current.valid()) {
            log.info("License runtime status changed to VALID; MCP tool calls are enabled according to licensed features");
        } else {
            log.warn("License runtime status changed to {}; MCP Server is in restricted mode: {}",
                current.status(), current.message());
        }
    }
}
