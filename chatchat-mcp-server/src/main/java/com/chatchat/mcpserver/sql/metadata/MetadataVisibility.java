package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.resolution.TableLocation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Projection of existing datasource restrictions; it grants no caller permissions. */
final class MetadataVisibility {
    private static final ObjectMapper JSON = new ObjectMapper();
    private MetadataVisibility() { }
    static boolean tableVisible(SqlDatasourceConfig asset, TableLocation table) {
        List<String> names = names(table.database(), table.schema(), table.table());
        List<String> allowed = rawValues(asset.getAllowedTablesJson());
        List<String> physicalNames = physicalNames(table.database(), table.schema(), table.table());
        return (allowed.isEmpty() || intersects(allowed, physicalNames)) && !intersects(values(asset.getSensitiveTablesJson()), names);
    }
    static boolean fieldVisible(SqlDatasourceConfig asset, TableLocation table, String field) {
        return fieldVisible(asset, names(table.database(), table.schema(), table.table()), field);
    }
    static boolean fieldVisible(SqlDatasourceConfig asset, List<String> objectNames, String field) {
        List<String> names = new ArrayList<>(); names.add(normalize(field));
        objectNames.forEach(name -> names.add(normalize(name) + "." + normalize(field)));
        return values(asset.getSensitiveFieldsJson()).stream().noneMatch(hidden -> names.stream()
            .anyMatch(name -> name.equals(hidden) || name.startsWith(hidden + ".")));
    }
    private static List<String> names(String database, String schema, String table) {
        return physicalNames(database, schema, table).stream().map(MetadataVisibility::normalize).toList();
    }
    private static List<String> physicalNames(String database, String schema, String table) {
        Set<String> names = new LinkedHashSet<>(); names.add(table);
        if (database != null && !database.isBlank()) names.add(database + "." + table);
        if (schema != null && !schema.isBlank()) names.add(schema + "." + table);
        if (database != null && !database.isBlank() && schema != null && !schema.isBlank()) names.add(database + "." + schema + "." + table);
        return List.copyOf(names);
    }
    private static boolean intersects(List<String> restrictions, List<String> names) { return restrictions.stream().anyMatch(names::contains); }
    private static List<String> values(String value) {
        return rawValues(value).stream().map(MetadataVisibility::normalize).toList();
    }
    private static List<String> rawValues(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            List<String> items = JSON.readValue(value, new TypeReference<List<String>>() { });
            if (items == null || items.stream().anyMatch(item -> item == null || item.isBlank())) throw new IllegalArgumentException();
            return items;
        } catch (Exception ex) { throw new IllegalStateException("Invalid metadata access scope", ex); }
    }
    private static String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
}
