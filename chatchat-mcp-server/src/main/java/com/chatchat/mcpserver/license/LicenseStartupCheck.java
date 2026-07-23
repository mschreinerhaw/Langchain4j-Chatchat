package com.chatchat.mcpserver.license;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RequiredArgsConstructor
@Slf4j
public class LicenseStartupCheck implements ApplicationRunner {
    private final McpLicenseService licenseService;
    private final LicenseProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (properties.isFailStartupOnInvalid()) {
            licenseService.requireRuntimeLicense();
            return;
        }
        var status = licenseService.status();
        if (!status.valid()) {
            log.warn("MCP Server started in restricted mode: status={}, message={}. "
                + "Administrative and License status pages remain available, but new tool calls are denied.",
                status.status(), status.message());
        }
    }
}
