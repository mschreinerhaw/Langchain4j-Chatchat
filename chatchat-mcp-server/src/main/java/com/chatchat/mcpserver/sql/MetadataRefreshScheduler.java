package com.chatchat.mcpserver.sql;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataRefreshScheduler {

    private final SqlDatasourceConfigService datasourceConfigService;
    private final MetadataIndexService metadataIndexService;
    private final Map<String, Long> lastRefreshMsByDatasource = new ConcurrentHashMap<>();

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
            long now = System.currentTimeMillis();
            metadataIndexService.refreshEnabledDatasources(datasourceConfigService.listEnabled().stream()
                .filter(datasource -> refreshDue(datasource, now))
                .peek(datasource -> lastRefreshMsByDatasource.put(datasource.getId(), now))
                .toList());
        } catch (Exception ex) {
            log.warn("Scheduled metadata index refresh failed: error={}", ex.getMessage());
        }
    }

    private boolean refreshDue(SqlDatasourceConfig datasource, long now) {
        if (datasource == null || datasource.getId() == null || !datasource.isMetadataAutoRefreshEnabled()) {
            return false;
        }
        long intervalMs = Math.max(5, datasource.getMetadataRefreshIntervalMinutes()) * 60_000L;
        Long lastRefreshMs = lastRefreshMsByDatasource.get(datasource.getId());
        return lastRefreshMs == null || now - lastRefreshMs >= intervalMs;
    }
}
