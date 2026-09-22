package com.chatchat.migration;

import com.chatchat.migration.DataMigrationApplication.Column;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Resolve schema drift without dropping source data or inventing arbitrary required fields. */
final class ColumnMigrationPlan {
    private ColumnMigrationPlan() { }

    static Plan create(String table, Set<String> sourceNames, LinkedHashMap<String, Column> targetColumns) {
        Set<String> sourceOnly = new TreeSet<>(sourceNames);
        sourceOnly.removeAll(targetColumns.keySet());
        if (!sourceOnly.isEmpty()) {
            throw new IllegalStateException("Column mismatch in " + table + "; source-only=" + sourceOnly
                    + ". Update the target schema before migrating to avoid dropping data.");
        }
        LinkedHashMap<String, Column> copied = new LinkedHashMap<>();
        for (String name : sourceNames) {
            copied.put(name, targetColumns.get(name));
        }
        List<Column> filled = new ArrayList<>();
        for (Column column : targetColumns.values()) {
            if (sourceNames.contains(column.name())) {
                continue;
            }
            if (column.nullable() || column.defaultValue() != null) {
                System.out.printf("%s.%s: source column absent; using target default/NULL%n", table, column.name());
            } else if (isAuditTimestamp(column)) {
                filled.add(column);
                System.out.printf("%s.%s: source column absent; using migration timestamp%n", table, column.name());
            } else {
                throw new IllegalStateException("Column mismatch in " + table + ": required target column "
                        + column.name() + " has no source value or database default");
            }
        }
        return new Plan(copied, List.copyOf(filled));
    }

    private static boolean isAuditTimestamp(Column column) {
        return Set.of("created_at", "updated_at").contains(column.name())
                && Set.of("datetime", "timestamp", "timestamp with time zone",
                        "timestamp without time zone").contains(column.type());
    }

    static void bindSynthetic(PreparedStatement target, int index, Column column, Instant migrationTime,
            ZoneId mysqlZone) throws SQLException {
        if (column.type().equals("timestamp with time zone")) {
            target.setObject(index, migrationTime.atOffset(ZoneOffset.UTC));
        } else {
            target.setObject(index, LocalDateTime.ofInstant(migrationTime, mysqlZone));
        }
    }

    record Plan(LinkedHashMap<String, Column> copied, List<Column> filled) { }
}
