package com.chatchat.runtime.market.analysis;

import com.chatchat.runtime.market.storage.FinancialDatasetDefinition;
import com.chatchat.runtime.market.storage.FinancialReadOperations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reports whether the physical financial read store contains every dataset required by a query. */
@Service
public class FinancialDatasetReadinessService {

    private static final Pattern TABLE_REFERENCE = Pattern.compile(
        "(?i)\\b(?:from|join)\\s+[`\"]?([a-zA-Z][a-zA-Z0-9_]*)[`\"]?");
    private final ObjectProvider<FinancialReadOperations> readProvider;

    @Autowired
    public FinancialDatasetReadinessService(
        @Qualifier("financialReadOperations") ObjectProvider<FinancialReadOperations> readProvider) {
        this.readProvider = readProvider;
    }

    public Readiness inspect(String sql) {
        Set<String> requiredTables = requiredDatasetTables(sql);
        FinancialReadOperations reads = readProvider.getIfAvailable();
        if (reads == null) {
            return Readiness.unavailable("FINANCIAL_STORAGE_UNAVAILABLE", requiredTables,
                "Financial read storage is not configured");
        }
        try {
            List<Map<String, Object>> rows = reads.queryForList(
                "select table_name,archive_table_name,last_observation_date,last_collected_at from market_asset_catalog");
            Set<String> collectedTables = new LinkedHashSet<>();
            Instant latestCollectedAt = null;
            for (Map<String, Object> row : rows) {
                String table = text(value(row, "table_name"));
                String archiveTable = text(value(row, "archive_table_name"));
                Set<String> rowTables = new LinkedHashSet<>();
                if (!table.isBlank()) rowTables.add(table.toLowerCase(Locale.ROOT));
                if (!archiveTable.isBlank()) rowTables.add(archiveTable.toLowerCase(Locale.ROOT));
                collectedTables.addAll(rowTables);
                Instant collectedAt = instant(value(row, "last_collected_at"));
                boolean receiptApplies = requiredTables.isEmpty()
                    || rowTables.stream().anyMatch(requiredTables::contains);
                if (receiptApplies && collectedAt != null
                    && (latestCollectedAt == null || collectedAt.isAfter(latestCollectedAt))) {
                    latestCollectedAt = collectedAt;
                }
            }
            Set<String> missing = new LinkedHashSet<>(requiredTables);
            missing.removeAll(collectedTables);
            if (!missing.isEmpty()) {
                return new Readiness(false, "DATASET_NOT_COLLECTED", Set.copyOf(requiredTables), Set.copyOf(missing),
                    latestCollectedAt, "Required financial datasets have not produced a successful ingestion receipt");
            }
            return new Readiness(true, "READY", Set.copyOf(requiredTables), Set.of(), latestCollectedAt,
                requiredTables.isEmpty() ? "Financial read storage is ready" : "Required financial datasets are collected");
        } catch (RuntimeException ex) {
            return Readiness.unavailable("FINANCIAL_STORAGE_UNAVAILABLE", requiredTables,
                "Financial read storage is not initialized: " + rootMessage(ex));
        }
    }

    Set<String> requiredDatasetTables(String sql) {
        Set<String> governed = FinancialDatasetDefinition.governedTableNames();
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TABLE_REFERENCE.matcher(sql == null ? "" : sql);
        while (matcher.find()) {
            String table = matcher.group(1).toLowerCase(Locale.ROOT);
            if (governed.contains(table)) result.add(table);
        }
        return Set.copyOf(result);
    }

    private Object value(Map<String, Object> row, String name) {
        if (row == null) return null;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.time.LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        return null;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public record Readiness(boolean ready, String status, Set<String> requiredTables, Set<String> missingTables,
                            Instant lastCollectedAt, String message) {
        static Readiness unavailable(String status, Set<String> requiredTables, String message) {
            return new Readiness(false, status, Set.copyOf(requiredTables), Set.copyOf(requiredTables), null, message);
        }
    }
}
