package com.chatchat.mcpserver.sql;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataRefreshScheduler {

    private final SqlDatasourceConfigService datasourceConfigService;
    private final MetadataIndexService metadataIndexService;

    @Value("${chatchat.mcp.sql.metadata-refresh-enabled:true}")
    private boolean refreshEnabled;

    @Scheduled(
        initialDelayString = "${chatchat.mcp.sql.metadata-refresh-initial-delay-ms:60000}",
        fixedDelayString = "${chatchat.mcp.sql.metadata-refresh-interval-ms:300000}"
    )
    public void refresh() {
        if (!refreshEnabled) {
            return;
        }
        try {
            metadataIndexService.refreshEnabledDatasources(datasourceConfigService.listEnabled());
        } catch (Exception ex) {
            log.warn("Scheduled metadata index refresh failed: error={}", ex.getMessage());
        }
    }
}
