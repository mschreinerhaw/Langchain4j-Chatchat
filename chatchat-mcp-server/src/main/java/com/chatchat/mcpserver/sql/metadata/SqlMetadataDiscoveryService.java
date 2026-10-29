package com.chatchat.mcpserver.sql.metadata;

import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.resolution.TableLocation;
import com.chatchat.mcpserver.search.engine.LuceneMcpSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Versioned, authorized projection of the existing metadata index. Never opens a business SQL executor. */
@Service
@RequiredArgsConstructor
public class SqlMetadataDiscoveryService {
    public static final String CONTRACT = "sql-studio.metadata.v2";
    public static final Set<String> OPERATIONS = Set.of("LIST_ASSETS", "LIST_NAMESPACES", "SEARCH", "DESCRIBE");
    private static final Set<String> FIELDS = Set.of("contractVersion", "operation", "assetId", "database", "schema",
        "tableName", "query", "page", "pageSize", "expectedSnapshotId", "includeColumns");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SqlDatasourceConfigService sources;
    private final MetadataIndexService indexes;
    private final LuceneMcpSearchService search;

    public Map<String, Object> discover(Map<String, Object> arguments, Predicate<String> authorizedDomains) {
        Objects.requireNonNull(authorizedDomains, "Trusted authorization is required");
        validate(arguments);
        String operation = text(arguments, "operation");
        int page = integer(arguments, "page", 1, 1_000_000);
        int size = integer(arguments, "pageSize", 25, 100);
        String assetId = text(arguments, "assetId");
        List<SqlDatasourceConfig> assets = sources.listEnabled().stream()
            .filter(asset -> authorizedDomains.test(asset.getId()))
            .filter(asset -> assetId == null || assetId.equals(asset.getId()))
            .sorted(Comparator.comparing(SqlDatasourceConfig::getId)).toList();
        if (assetId != null && assets.isEmpty()) throw new SecurityException("Metadata asset is unavailable or unauthorized");
        if (!operation.equals("LIST_ASSETS") && assetId == null)
            throw new IllegalArgumentException("assetId is required for " + operation);
        if (operation.equals("LIST_ASSETS")) {
            var items = assets.stream().map(this::assetProjection).toList();
            return result(arguments, items, hash(items), List.of(), page, size, "SUCCEEDED");
        }

        SqlDatasourceConfig asset = assets.get(0);
        // Capture exactly one index revision. Do not mix columns from a subsequent refresh into this response.
        MetadataIndex index = indexes.indexFor(asset);
        if (index.error() != null) {
            Map<String, Object> result = result(arguments, List.of(), null, List.of(), page, size, "NEEDS_REFRESH");
            result.put("limitations", List.of("Metadata snapshot is unavailable; collect it through the existing metadata administration service."));
            return result;
        }
        // The legacy collector caps columns at 50,000. A truncated composite key
        // must never be mistaken for a complete key and certify JOIN uniqueness.
        boolean columnsComplete = index.tableColumns().values().stream().mapToLong(List::size).sum() < 50000;
        List<Map<String, Object>> tables = index.tables().stream()
            .filter(table -> MetadataVisibility.tableVisible(asset, table))
            .sorted(Comparator.comparing(table -> identity(table.database(), table.schema(), table.table())))
            .map(table -> tableProjection(asset, index, table, columnsComplete)).toList();
        String snapshot = hash(List.of(assetProjection(asset), tables));
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("assetRef", asset.getId());
        provenance.put("snapshotId", snapshot);
        provenance.put("source", "registered_metadata_index");
        provenance.put("sourceDialect", SqlDatasourceConfigService.normalizeDatabaseTypeToken(asset.getDatabaseType()));
        provenance.put("collectedAt", index.refreshedAtMs() > 0 ? java.time.Instant.ofEpochMilli(index.refreshedAtMs()).toString() : null);
        provenance.put("sourceUpdatedAt", null); // Collection time is not a database DDL timestamp.
        List<Map<String, Object>> matches = tables.stream()
            .filter(table -> matches(arguments, table, "database") && matches(arguments, table, "schema"))
            .filter(table -> text(arguments, "tableName") == null || Objects.equals(table.get("tableName"), text(arguments, "tableName")))
            .toList();
        if (operation.equals("LIST_NAMESPACES")) {
            Map<String, Map<String, Object>> namespaces = new TreeMap<>();
            matches.forEach(table -> {
                Map<String, Object> namespace = new LinkedHashMap<>();
                namespace.put("database", table.get("database")); namespace.put("schema", table.get("schema"));
                namespaces.put(identity((String) table.get("database"), (String) table.get("schema")), namespace);
            });
            matches = List.copyOf(namespaces.values());
        } else if (operation.equals("SEARCH")) {
            matches = rank(asset, matches, text(arguments, "query"));
        } else if (operation.equals("DESCRIBE")) {
            if (text(arguments, "tableName") == null) throw new IllegalArgumentException("tableName is required for DESCRIBE");
            if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous table; specify database and schema");
        }
        if (Boolean.FALSE.equals(arguments.get("includeColumns"))) matches = matches.stream().map(item -> {
            Map<String, Object> lightweight = new LinkedHashMap<>(item); lightweight.remove("columns"); return lightweight;
        }).toList();
        return result(arguments, matches, snapshot, List.of(provenance), page, size, "SUCCEEDED");
    }

    public void validate(Map<String, Object> args) {
        if (args == null || !CONTRACT.equals(args.get("contractVersion")))
            throw new IllegalArgumentException("contractVersion must be " + CONTRACT);
        if (!FIELDS.containsAll(args.keySet())) throw new IllegalArgumentException("Unsupported metadata argument");
        if (args.containsKey("includeColumns") && !(args.get("includeColumns") instanceof Boolean))
            throw new IllegalArgumentException("includeColumns must be boolean");
        if (text(args, "operation") == null || !OPERATIONS.contains(text(args, "operation"))) throw new IllegalArgumentException("Unsupported metadata operation");
        for (String key : List.of("operation", "assetId", "database", "schema", "tableName", "query", "expectedSnapshotId")) {
            Object value = args.get(key);
            if (value != null && (!(value instanceof String) || ((String) value).isBlank() || ((String) value).length() > 2000))
                throw new IllegalArgumentException("Invalid metadata argument: " + key);
        }
    }

    private List<Map<String, Object>> rank(SqlDatasourceConfig asset, List<Map<String, Object>> tables, String query) {
        if (query == null) return tables;
        List<LuceneMcpSearchService.AssetDoc> docs = tables.stream().map(table ->
            new LuceneMcpSearchService.AssetDoc((String) table.get("tableRef"), "sql_datasource", (String) table.get("tableName"),
                (String) table.get("tableName"), asset.getToolName(), asset.getEnvironment(), asset.getDatabaseType(), List.of(),
                "metadata_table", asset.getId(), (String) table.get("database"), (String) table.get("tableName"),
                (String) table.get("tableName"), serialize(table.get("columns")), (String) table.get("comment"), null)).toList();
        Map<String, Float> scores = search.rankScopedMetadata(docs, query);
        return tables.stream().filter(table -> scores.containsKey(table.get("tableRef")))
            .sorted(Comparator.<Map<String, Object>>comparingDouble(table -> scores.get(table.get("tableRef"))).reversed()
                .thenComparing(table -> (String) table.get("tableRef"))).toList();
    }

    private Map<String, Object> tableProjection(SqlDatasourceConfig asset, MetadataIndex index, TableLocation table, boolean columnsComplete) {
        String key = "trino".equals(index.databaseType())
            ? MetadataIndex.tableKey(Objects.toString(table.database(), ""), Objects.toString(table.schema(), ""), table.table())
            : (Objects.toString(table.database(), "") + "." + table.table()).toLowerCase(Locale.ROOT);
        List<MetadataColumn> allColumns = index.tableColumns().getOrDefault(key, List.of()).stream()
            // Legacy index keys may merge namespaces/case variants. Never attach another physical object's fields.
            .filter(column -> Objects.equals(column.datasourceId(), asset.getId())
                && Objects.equals(column.database(), table.database()) && Objects.equals(column.schema(), table.schema())
                && Objects.equals(column.table(), table.table()))
            .toList();
        List<MetadataColumn> columns = allColumns.stream()
            .filter(column -> MetadataVisibility.fieldVisible(asset, table, column.name()))
            .sorted(Comparator.comparing(MetadataColumn::name)).toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("tableRef", hash(List.of(asset.getId(), identity(table.database(), table.schema(), table.table()))));
        value.put("database", table.database()); value.put("schema", table.schema());
        value.put("tableName", table.table()); value.put("tableType", table.tableType()); value.put("comment", table.tableComment());
        value.put("columns", columns.stream().map(MetadataColumn::toDiagnostic).toList());
        value.put("columnCount", columns.size());
        // Only MySQL's documented PRI marker establishes a primary key here. Other
        // collectors (including Inceptor's unique_constraint) do not prove a PK.
        boolean primarySource = columnsComplete && "mysql".equals(index.databaseType());
        var primary = primarySource ? allColumns.stream().filter(c -> "PRI".equals(c.columnKey())).map(MetadataColumn::name).sorted().toList() : List.<String>of();
        var partitions = allColumns.stream().flatMap(c -> c.partitionKeys().stream()).distinct().sorted().toList();
        var visible = columns.stream().map(MetadataColumn::name).collect(Collectors.toSet());
        boolean primaryVisible = visible.containsAll(primary), partitionVisible = visible.containsAll(partitions);
        value.put("primaryKeys", primaryVisible ? primary : List.of());
        value.put("primaryKeyStatus", primary.isEmpty() ? "NOT_COLLECTED" : primaryVisible ? "COLLECTED_VISIBLE" : "REDACTED");
        value.put("partitionKeys", partitionVisible ? partitions : List.of());
        value.put("partitionKeyStatus", partitions.isEmpty() ? "NOT_COLLECTED" : partitionVisible ? "COLLECTED_VISIBLE" : "REDACTED");
        value.put("businessGrainStatus", "UNCONFIRMED");
        var relationshipKey = MetadataIndex.relationshipKey(table.database(), table.schema(), table.table());
        value.put("relationsStatus", index.foreignKeys().containsKey(relationshipKey) ? "COLLECTED_VISIBLE" : "NOT_COLLECTED");
        value.put("foreignKeys", visibleForeignKeys(asset, index, table, columns));
        value.put("evidenceClass", "REGISTERED_METADATA");
        value.put("verificationStatus", "NOT_VERIFIED_LIVE");
        return value;
    }

    private List<Map<String,Object>> visibleForeignKeys(SqlDatasourceConfig asset, MetadataIndex index, TableLocation table, List<MetadataColumn> columns) {
        var groups = new TreeMap<String,List<MetadataForeignKey>>();
        for (var key : index.foreignKeys().getOrDefault(MetadataIndex.relationshipKey(table.database(), table.schema(), table.table()), List.of())) {
            // An unnamed composite constraint cannot safely be grouped from individual rows.
            if (key.name() != null) groups.computeIfAbsent(key.name(), ignored -> new ArrayList<>()).add(key);
        }
        var output = new ArrayList<Map<String,Object>>();
        for (var group : groups.entrySet()) {
            boolean visible = group.getValue().stream().allMatch(key -> columns.stream().anyMatch(c -> c.name().equals(key.column()))
                && index.tables().stream().anyMatch(target -> Objects.equals(target.database(), key.referencedDatabase())
                    && Objects.equals(target.schema(), key.referencedSchema()) && Objects.equals(target.table(), key.referencedTable())
                    && MetadataVisibility.tableVisible(asset, target) && MetadataVisibility.fieldVisible(asset, target, key.referencedColumn())
                    && index.tableColumns().values().stream().flatMap(List::stream).anyMatch(c -> Objects.equals(c.datasourceId(), asset.getId())
                        && Objects.equals(c.database(), target.database()) && Objects.equals(c.schema(), target.schema())
                        && Objects.equals(c.table(), target.table()) && Objects.equals(c.name(), key.referencedColumn()))));
            if (!visible) continue; // Hide the entire constraint, including all pairs and its name.
            for (var key : group.getValue().stream().sorted(Comparator.comparingInt(MetadataForeignKey::keySequence)).toList()) {
                var row = new LinkedHashMap<String,Object>();
                row.put("constraintRef", hash(List.of(asset.getId(), table.table(), group.getKey())));
                row.put("column", key.column()); row.put("referencedDatabase", key.referencedDatabase()); row.put("referencedSchema", key.referencedSchema());
                row.put("referencedTable", key.referencedTable()); row.put("referencedColumn", key.referencedColumn()); row.put("keySequence", key.keySequence());
                row.put("source", "JDBC_IMPORTED_KEYS"); output.add(row);
            }
        }
        return output;
    }

    private Map<String, Object> assetProjection(SqlDatasourceConfig asset) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("assetRef", asset.getId()); value.put("name", asset.getName()); value.put("displayName", asset.getTitle());
        value.put("environment", asset.getEnvironment());
        value.put("sourceDialect", SqlDatasourceConfigService.normalizeDatabaseTypeToken(asset.getDatabaseType()));
        return value;
    }

    private Map<String, Object> result(Map<String, Object> args, List<Map<String, Object>> items, String snapshot,
                                       List<Map<String, Object>> provenance, int page, int size, String status) {
        String expected = text(args, "expectedSnapshotId");
        if (expected != null && !expected.equals(snapshot)) throw new IllegalStateException("METADATA_SNAPSHOT_CHANGED: restart pagination with the current snapshot");
        if (page > 1 && expected == null) throw new IllegalArgumentException("expectedSnapshotId is required after the first page");
        int from = (int) Math.min((long) (page - 1) * size, items.size());
        int to = Math.min(from + size, items.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", CONTRACT); result.put("operation", args.get("operation")); result.put("status", status);
        result.put("snapshotId", snapshot); result.put("provenance", provenance);
        result.put("page", page); result.put("pageSize", size); result.put("totalMatched", items.size());
        result.put("hasMore", to < items.size()); result.put("items", items.subList(from, to)); result.put("doesNotExecuteSql", true);
        result.put("supportedOperations", OPERATIONS.stream().sorted().toList());
        result.put("evidenceClass", "REGISTERED_METADATA");
        result.put("verificationStatus", "NOT_VERIFIED_LIVE");
        return result;
    }

    private static boolean matches(Map<String, Object> args, Map<String, Object> table, String key) {
        return text(args, key) == null || Objects.equals(text(args, key), table.get(key));
    }
    private static int integer(Map<String, Object> args, String key, int fallback, int max) {
        Object raw = args.get(key);
        if (raw == null) return fallback;
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.intValue() || n.intValue() < 1 || n.intValue() > max)
            throw new IllegalArgumentException(key + " is outside its supported integer range");
        return ((Number) raw).intValue();
    }
    private static String text(Map<String, Object> args, String key) { return args.get(key) instanceof String text ? text : null; }
    private static String identity(String... parts) {
        return Arrays.stream(parts).map(part -> part == null ? "-:" : part.length() + ":" + part).collect(Collectors.joining());
    }
    private static String serialize(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("Cannot serialize metadata projection", ex); }
    }
    private static String hash(Object value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialize(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("Cannot version metadata projection", ex); }
    }
}
