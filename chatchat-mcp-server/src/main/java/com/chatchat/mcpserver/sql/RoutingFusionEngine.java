package com.chatchat.mcpserver.sql;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoutingFusionEngine {

    private final MetadataUsageHistoryService usageHistoryService;

    public double finalScore(double routingScore, double metadataScore, TableLocation location) {
        double historyScore = usageHistoryService.historyScore(location);
        return Math.min(1.0, routingScore * 0.4 + metadataScore * 0.4 + historyScore * 0.2);
    }
}
