package com.chatchat.mcpserver.sql;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MetadataUsageHistoryService {

    private final Map<String, TableOccurrence> occurrences = new ConcurrentHashMap<>();

    public void record(TableLocation location) {
        if (location == null || location.datasourceId() == null || location.table() == null) {
            return;
        }
        String key = key(location.datasourceId(), location.database(), location.table());
        occurrences.compute(key, (ignored, existing) -> new TableOccurrence(
            location.datasourceId(),
            location.database(),
            location.table(),
            existing == null ? 1 : existing.queryFrequency() + 1,
            System.currentTimeMillis()
        ));
    }

    public double historyScore(TableLocation location) {
        TableOccurrence occurrence = occurrence(location);
        if (occurrence == null) {
            return 0.0;
        }
        double frequency = Math.min(1.0, occurrence.queryFrequency() / 10.0);
        long ageMs = Math.max(0, System.currentTimeMillis() - occurrence.lastUsedTime());
        double recency = ageMs > 3_600_000 ? 0.2 : 1.0;
        return Math.min(1.0, frequency * 0.7 + recency * 0.3);
    }

    public List<TableOccurrence> historyFor(String tableName) {
        String normalized = normalize(tableName);
        if (normalized == null) {
            return List.of();
        }
        return occurrences.values().stream()
            .filter(occurrence -> normalized.equals(normalize(occurrence.table())))
            .sorted(Comparator.comparingInt(TableOccurrence::queryFrequency).reversed()
                .thenComparing(TableOccurrence::lastUsedTime, Comparator.reverseOrder()))
            .toList();
    }

    private TableOccurrence occurrence(TableLocation location) {
        if (location == null) {
            return null;
        }
        return occurrences.get(key(location.datasourceId(), location.database(), location.table()));
    }

    private String key(String datasourceId, String schema, String table) {
        return normalize(datasourceId) + ":" + normalize(schema) + ":" + normalize(table);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
