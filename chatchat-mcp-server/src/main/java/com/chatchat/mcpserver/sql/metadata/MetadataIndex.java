package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.sql.resolution.TableLocation;

import java.util.List;
import java.util.Map;

public record MetadataIndex(
    String datasourceId,
    String databaseType,
    List<TableLocation> tables,
    Map<String, List<TableLocation>> tableIndex,
    Map<String, List<String>> schemaTables,
    Map<String, List<MetadataColumn>> tableColumns,
    List<String> datasourceSchemas,
    long refreshedAtMs,
    boolean cacheHit,
    String error,
    List<MetadataObject> objects,
    String configurationFingerprint,
    List<Change> changes
) {
    public MetadataIndex {
        objects = objects == null ? relationalObjects(datasourceId, databaseType, tables, tableColumns) : List.copyOf(objects);
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    public MetadataIndex(String datasourceId, String databaseType, List<TableLocation> tables,
                         Map<String, List<TableLocation>> tableIndex, Map<String, List<String>> schemaTables,
                         Map<String, List<MetadataColumn>> tableColumns, List<String> datasourceSchemas,
                         long refreshedAtMs, boolean cacheHit, String error) {
        this(datasourceId, databaseType, tables, tableIndex, schemaTables, tableColumns, datasourceSchemas,
            refreshedAtMs, cacheHit, error, null, null, List.of());
    }

    public MetadataIndex withFingerprint(String fingerprint) {
        return new MetadataIndex(datasourceId, databaseType, tables, tableIndex, schemaTables, tableColumns,
            datasourceSchemas, refreshedAtMs, cacheHit, error, objects, fingerprint, changes);
    }

    public MetadataIndex withChanges(MetadataIndex previous) {
        if (previous == null || previous.error() != null) return this;
        Map<String, MetadataObject> oldObjects = previous.objects().stream().collect(java.util.stream.Collectors.toMap(MetadataObject::id, value -> value));
        List<Change> changes = new java.util.ArrayList<>(previous.changes());
        for (MetadataObject object : objects) {
            MetadataObject old = oldObjects.remove(object.id());
            if (old != null && !old.equals(object)) changes.add(new Change(object.id(), object.namespace(), object.name(), object.qualifiedName(), "CHANGED", refreshedAtMs));
        }
        for (MetadataObject removed : oldObjects.values()) changes.add(new Change(removed.id(), removed.namespace(), removed.name(), removed.qualifiedName(), "REMOVED", refreshedAtMs));
        Map<String, Change> latest = new java.util.LinkedHashMap<>();
        changes.forEach(change -> latest.put(change.objectId(), change));
        return new MetadataIndex(datasourceId, databaseType, tables, tableIndex, schemaTables, tableColumns,
            datasourceSchemas, refreshedAtMs, cacheHit, error, objects, configurationFingerprint,
            latest.values().stream().sorted(java.util.Comparator.comparingLong(Change::changedAtMs).reversed()).limit(20000).toList());
    }

    public record Change(String objectId, String namespace, String name, String qualifiedName, String status, long changedAtMs) {}

    public static MetadataIndex collected(String datasourceId, String type, List<MetadataObject> objects) {
        // Native structures have their own objects; SQL consumers never mistake labels or indices for tables.
        var tables = new java.util.ArrayList<TableLocation>();
        Map<String, List<MetadataColumn>> columns = new java.util.LinkedHashMap<>();
        if ("trino".equals(type)) {
            for (MetadataObject object : objects) {
                String catalog = object.path().get(0), schema = object.path().get(1);
                tables.add(new TableLocation(datasourceId, catalog, schema, object.name(), object.kind(), null, object.description(), null, 0));
                columns.put(tableKey(catalog, schema, object.name()), object.fields().stream().map(field -> new MetadataColumn(
                    datasourceId, catalog, schema, object.name(), field.name(), field.nativeType(), field.nativeType(), null,
                    field.description(), !Boolean.FALSE.equals(field.nullable()),
                    field.attributes().get("ordinalPosition") instanceof Number n ? n.intValue() : null)).toList());
            }
        }
        var tableIndex = tables.stream().collect(java.util.stream.Collectors.groupingBy(table -> table.table().toLowerCase(java.util.Locale.ROOT)));
        var schemaTables = tables.stream().collect(java.util.stream.Collectors.groupingBy(table -> (table.database() + "." + table.schema()).toLowerCase(java.util.Locale.ROOT),
            java.util.stream.Collectors.mapping(TableLocation::table, java.util.stream.Collectors.toList())));
        return new MetadataIndex(datasourceId, type, tables, tableIndex, schemaTables, columns,
            objects.stream().map(MetadataObject::namespace).distinct().sorted().toList(), System.currentTimeMillis(), false, null, objects, null, List.of());
    }

    public static String tableKey(String catalog, String schema, String table) {
        return List.of(catalog, schema, table).stream().map(value -> value.toLowerCase(java.util.Locale.ROOT))
            .map(value -> value.length() + ":" + value).collect(java.util.stream.Collectors.joining());
    }

    private static List<MetadataObject> relationalObjects(String id, String type, List<TableLocation> tables, Map<String, List<MetadataColumn>> columns) {
        if (tables == null || columns == null) return List.of();
        return tables.stream().map(table -> {
            String namespace = table.database();
            List<MetadataColumn> fields = columns.getOrDefault((namespace + "." + table.table()).toLowerCase(java.util.Locale.ROOT), List.of());
            return new MetadataObject(id, type, "VIEW".equalsIgnoreCase(table.tableType()) ? "VIEW" : "TABLE", namespace, table.table(),
                List.of(namespace, table.table()), table.tableComment(), fields.stream().map(field -> new MetadataObject.Field(field.name(), List.of(field.name()),
                    field.columnType() == null ? field.dataType() : field.columnType(), field.nullable(), field.comment(), Map.of())).toList(), Map.of("source", "relational_metadata"));
        }).toList();
    }
    public static MetadataIndex failed(String datasourceId, String databaseType, String error) {
        return new MetadataIndex(
            datasourceId,
            databaseType,
            List.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of(),
            System.currentTimeMillis(),
            false,
            error
        );
    }
}
