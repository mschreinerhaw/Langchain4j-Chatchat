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
    public static final Set<String> OPERATIONS = Set.of("LIST_ASSETS", "LIST_NAMESPACES", "SEARCH", "DESCRIBE",
        "LIST_CATALOGS", "LIST_SCHEMAS", "LIST_TABLES", "SEARCH_TABLES", "SEARCH_COLUMNS", "DESCRIBE_TABLE",
        "GET_TABLE_DDL", "GET_TABLE_RELATIONSHIPS", "GET_METADATA_FRESHNESS", "FIND_RELATED_TABLES", "VERIFY_LIVE_SCHEMA", "SEARCH_SQL_EXAMPLES", "REFRESH");
    private static final Set<String> FIELDS = Set.of("contractVersion", "operation", "assetId", "database", "schema",
        "tableName", "query", "page", "pageSize", "expectedSnapshotId", "includeColumns", "reason");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SqlDatasourceConfigService sources;
    private final MetadataIndexService indexes;
    private final LuceneMcpSearchService search;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService templates;
    private record Projection(Object tables,Object columns,long collected,String policy,List<Map<String,Object>> rows,String version) {}
    private final Map<String,Projection> projections=new java.util.concurrent.ConcurrentHashMap<>();

    /** A projection is immutable for one collected revision and current visibility policy. No source read. */
    public List<Map<String,Object>> indexEvidence(SqlDatasourceConfig asset,MetadataIndex index){
        return projection(asset,index).rows();
    }
    private Projection projection(SqlDatasourceConfig asset,MetadataIndex index){
        String policy=MetadataScopes.fingerprint(asset);
        if(projections.size()>512)projections.clear();
        return projections.compute(asset.getId(),(key,old)->{
            if(old!=null&&old.tables()==index.tables()&&old.columns()==index.tableColumns()
                    &&old.collected()==index.refreshedAtMs()&&old.policy().equals(policy))return old;
            boolean complete=index.tableColumns().values().stream().mapToLong(List::size).sum()<50000;
            var rows=index.tables().stream().filter(t->MetadataVisibility.tableVisible(asset,t))
                .sorted(Comparator.comparing(t->identity(t.database(),t.schema(),t.table())))
                .map(t->tableProjection(asset,index,t,complete)).toList();
            String version=hash(List.of(assetProjection(asset),rows));
            var stored=rows.stream().map(t->{var value=new LinkedHashMap<String,Object>(t);
                value.put("snapshotId",version);value.put("collectedAt",index.refreshedAtMs());
                value.put("schemaVersion",hash(t));value.put("viewDefinitionStatus","NOT_COLLECTED");
                return Collections.unmodifiableMap(value);}).toList();
            return new Projection(index.tables(),index.tableColumns(),index.refreshedAtMs(),policy,stored,version);
        });
    }

    public Map<String, Object> discover(Map<String, Object> arguments, Predicate<String> authorizedDomains) {
        long started=System.nanoTime();
        var value=discoverInternal(arguments,authorizedDomains);
        value.put("durationMs",(System.nanoTime()-started)/1_000_000);
        value.putIfAbsent("sourceReadCount","REFRESH".equals(arguments.get("operation"))?1:0);
        return value;
    }
    private Map<String,Object> discoverInternal(Map<String,Object> arguments,Predicate<String> authorizedDomains){
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
        if(operation.equals("SEARCH_SQL_EXAMPLES")){
            String query=Objects.toString(text(arguments,"query"),"").trim();
            if(query.isBlank()||query.length()>512)throw new IllegalArgumentException("Supply a bounded SQL example query");
            if(templates==null)return result(arguments,List.of(),hash(List.of(assetProjection(asset))),List.of(),page,size,"CAPABILITY_UNAVAILABLE");
            var terms=Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+")).filter(s->!s.isBlank()).limit(12).toList();
            var examples=new ArrayList<Map<String,Object>>();
            // The existing catalog intersects TEMPLATE read grants, tenant, asset ACL and template allowlists.
            // Read its already authorized content only; never invoke a template executor.
            for(var template:templates.businessTemplates(false)){
                if(!asset.getId().equals(template.binding().get("datasourceId")))continue;
                String content=templates.readableContent(template.templateKey());
                String text=(template.title()+" "+template.description()+" "+content).toLowerCase(Locale.ROOT);
                long score=terms.stream().filter(text::contains).count();if(score==0)continue;
                var row=new LinkedHashMap<String,Object>(template.publicView());
                row.put("assetRef",asset.getId());row.put("sql",content.length()>8000?content.substring(0,8000):content);
                row.put("contentTruncated",content.length()>8000);row.put("evidenceSource","AUTHORIZED_TEMPLATE_ASSET_CATALOG");
                row.put("referenceAuthority","REFERENCE_ONLY_NOT_PHYSICAL_SCHEMA_OR_EXECUTION_AUTHORITY");row.put("score",score);examples.add(row);
            }
            examples.sort(Comparator.<Map<String,Object>>comparingLong(row->((Number)row.get("score")).longValue()).reversed());
            var response=result(arguments,examples,hash(examples),List.of(Map.of("source","AUTHORIZED_TEMPLATE_ASSET_CATALOG","assetRef",asset.getId())),page,size,"SUCCEEDED");
            response.put("sqlExecuted",false);response.put("sourceReadCount",0);return response;
        }
        if (operation.equals("REFRESH")) {
            var refreshed = indexes.refreshDatasource(asset,text(arguments,"reason"));
            if (refreshed.error() != null) {
                var failed = result(arguments, List.of(), null, List.of(), page, size, "CONNECTION_FAILED");
                failed.put("category", "METADATA_CONNECTION_FAILED");
                failed.put("limitations", List.of("Metadata refresh failed; previous snapshot was retained."));
                return failed;
            }
        }
        // Capture exactly one index revision. Do not mix columns from a subsequent refresh into this response.
        MetadataIndex index = indexes.indexFor(asset);
        if(operation.equals("VERIFY_LIVE_SCHEMA")){
            String reason=text(arguments,"reason"),name=text(arguments,"tableName");
            if(reason==null||reason.isBlank()||reason.length()>512||name==null)throw new IllegalArgumentException("Live verification requires an exact table and a bounded reason");
            long at=System.nanoTime();
            try{
                var verified=indexes.verifyTable(asset,text(arguments,"database"),text(arguments,"schema"),name,reason);
                var physical=verified.tables().stream().filter(t->t.table().equals(name))
                    .filter(t->text(arguments,"database")==null||Objects.equals(t.database(),text(arguments,"database")))
                    .filter(t->text(arguments,"schema")==null||Objects.equals(t.schema(),text(arguments,"schema"))).toList();
                if(physical.size()!=1)throw new IllegalArgumentException("Specify a single live namespace");
                var row=tableProjection(asset,verified,physical.get(0),true);
                String snapshot=index.error()==null?projection(asset,index).version():"live:"+hash(List.of(assetProjection(asset),MetadataScopes.fingerprint(asset)));
                row.put("schemaVersion",hash(row));row.put("snapshotId",snapshot);row.put("evidenceSource","JDBC_LIVE_METADATA");
                row.put("verifiedAt",System.currentTimeMillis());row.put("verificationReason",reason);row.put("verificationStatus","LIVE_STRUCTURE_VERIFIED");
                var provenance=new LinkedHashMap<String,Object>();provenance.put("source","JDBC_LIVE_METADATA");provenance.put("assetRef",asset.getId());
                provenance.put("reason",reason);provenance.put("sourceQueryMs",(System.nanoTime()-at)/1_000_000);provenance.put("sourceReadCount",1);
                var result=result(arguments,List.of(row),snapshot,List.of(provenance),page,size,"SUCCEEDED");result.put("sourceReadCount",1);
                result.put("verificationStatus","LIVE_STRUCTURE_VERIFIED_SQL_NOT_EXECUTED");return result;
            }catch(SecurityException denied){throw denied;}
            catch(IllegalArgumentException invalid){throw invalid;}
            catch(Exception unavailable){throw new IllegalStateException("METADATA_LIVE_VERIFICATION_FAILED: "+unavailable.getClass().getSimpleName());}
        }
        if (index.error() != null) {
            if(operation.equals("GET_METADATA_FRESHNESS"))return result(arguments,List.of(Map.of("availability","MISSING","completeness","MISSING","sourceUpdatedAt","UNKNOWN")),
                "live:"+hash(List.of(assetProjection(asset),MetadataScopes.fingerprint(asset))),List.of(),page,size,"SUCCEEDED");
            Map<String, Object> result = result(arguments, List.of(), null, List.of(), page, size, "NEEDS_REFRESH");
            result.put("limitations", List.of("Metadata snapshot is unavailable; collect it through the existing metadata administration service."));
            return result;
        }
        var projected=projection(asset,index);
        List<Map<String,Object>> tables=projected.rows();
        String snapshot=projected.version();
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("assetRef", asset.getId());
        provenance.put("snapshotId", snapshot);
        provenance.put("source", "registered_metadata_index");
        provenance.put("sourceDialect", SqlDatasourceConfigService.normalizeDatabaseTypeToken(asset.getDatabaseType()));
        provenance.put("collectedAt", index.refreshedAtMs() > 0 ? java.time.Instant.ofEpochMilli(index.refreshedAtMs()).toString() : null);
        provenance.put("sourceUpdatedAt", null); // Collection time is not a database DDL timestamp.
        if(operation.equals("GET_METADATA_FRESHNESS")){
            var state=new LinkedHashMap<String,Object>();state.put("snapshotId",snapshot);state.put("collectedAt",index.refreshedAtMs());
            state.put("ageMs",Math.max(0,System.currentTimeMillis()-index.refreshedAtMs()));state.put("source","REGISTERED_METADATA_INDEX");
            state.put("completeness",index.tableColumns().values().stream().mapToLong(List::size).sum()>=50000?"TRUNCATED":"COLLECTED");
            state.put("viewDefinitions","NOT_COLLECTED");state.put("sourceUpdatedAt","UNKNOWN");
            return result(arguments,List.of(state),snapshot,List.of(provenance),page,size,"SUCCEEDED");
        }
        List<Map<String, Object>> matches = tables.stream()
            .filter(table -> matches(arguments, table, "database") && matches(arguments, table, "schema"))
            .filter(table -> text(arguments, "tableName") == null || Objects.equals(table.get("tableName"), text(arguments, "tableName")))
            .toList();
        if(Set.of("SEARCH","SEARCH_TABLES","SEARCH_COLUMNS","DESCRIBE","DESCRIBE_TABLE","GET_TABLE_DDL","GET_TABLE_RELATIONSHIPS","FIND_RELATED_TABLES").contains(operation)){
            String query=text(arguments,"query");
            boolean related=operation.equals("FIND_RELATED_TABLES");
            if(related){
                var anchor=matches;
                if(text(arguments,"tableName")==null||anchor.size()!=1)throw new IllegalArgumentException("Specify one table and namespace for relationship exploration");
                query=columnNames(anchor.get(0)).stream().filter(n->!n.equalsIgnoreCase("id")).limit(16).collect(Collectors.joining(" "));
                matches=tables.stream().filter(t->matches(arguments,t,"database")&&matches(arguments,t,"schema")).toList();
            }
            long lookup=System.nanoTime();
            var indexed=search.indexedMetadata(asset.getId(),matches.stream().map(t->t.get("tableRef").toString()).toList(),query,related?null:text(arguments,"tableName"));
            if(indexed.isPresent()){
                var visible=matches.stream().collect(Collectors.toMap(t->t.get("tableRef").toString(),t->t));
                var read=indexed.get();
                if(read.stream().anyMatch(t->!snapshot.equals(t.get("snapshotId")))){
                    var stale=result(arguments,List.of(),snapshot,List.of(provenance),page,size,"INDEX_STALE");
                    stale.put("category","METADATA_INDEX_STALE");stale.put("limitation","Indexed evidence differs from the current authorized revision. Verify the specific object with VERIFY_LIVE_SCHEMA or synchronize the index.");return stale;
                }
                matches=read.stream().filter(t->visible.containsKey(t.get("tableRef"))).filter(t->sameEvidence(visible.get(t.get("tableRef")),t)).toList();
                provenance.put("source","OPENSEARCH");provenance.put("indexQueryMs",(System.nanoTime()-lookup)/1_000_000);
                provenance.put("indexQueryCount",1);
                if(matches.isEmpty()&&!visible.isEmpty()){
                    var missing=result(arguments,List.of(),snapshot,List.of(provenance),page,size,"INDEX_MISS");
                    missing.put("category","METADATA_INDEX_MISS");missing.put("limitation","No matching complete indexed evidence was returned. This does not prove an object or field is absent. Expand the query, synchronize registered metadata, or verify the requested exact object with a reason.");return missing;
                }
            }else if(Set.of("SEARCH","SEARCH_TABLES","SEARCH_COLUMNS").contains(operation))matches=rank(asset,matches,query);
            if(related){
                var anchor=tables.stream().filter(t->Objects.equals(t.get("tableName"),text(arguments,"tableName"))).filter(t->matches(arguments,t,"database")&&matches(arguments,t,"schema")).findFirst().orElseThrow();
                var names=columnNames(anchor);
                matches=matches.stream().filter(t->!anchor.get("tableRef").equals(t.get("tableRef"))).map(t->{var value=new LinkedHashMap<String,Object>(t);
                    value.put("sharedFields",columnNames(t).stream().filter(names::contains).toList());
                    value.put("relationshipEvidence","CANDIDATE_FIELD_NAME_MATCH_NOT_VERIFIED_FOREIGN_KEY");return (Map<String,Object>)value;})
                    .filter(t->!((List<?>)t.get("sharedFields")).isEmpty()).toList();
            }
        }
        if (Set.of("LIST_NAMESPACES", "LIST_CATALOGS", "LIST_SCHEMAS").contains(operation)) {
            Map<String, Map<String, Object>> namespaces = new TreeMap<>();
            matches.forEach(table -> {
                Map<String, Object> namespace = new LinkedHashMap<>();
                namespace.put("database", table.get("database"));
                if (!operation.equals("LIST_CATALOGS")) namespace.put("schema", table.get("schema"));
                namespaces.put(identity((String) table.get("database"), operation.equals("LIST_CATALOGS") ? null : (String) table.get("schema")), namespace);
            });
            matches = List.copyOf(namespaces.values());
        } else if (Set.of("DESCRIBE", "DESCRIBE_TABLE", "GET_TABLE_DDL", "GET_TABLE_RELATIONSHIPS").contains(operation)) {
            if (text(arguments, "tableName") == null) throw new IllegalArgumentException("tableName is required for DESCRIBE");
            if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous table; specify database and schema");
            if (matches.stream().anyMatch(table -> !(table.get("columns") instanceof List<?> fields) || fields.isEmpty())) {
                var incomplete=result(arguments,matches,snapshot,List.of(provenance),page,size,"METADATA_INCOMPLETE");
                incomplete.put("category","METADATA_INCOMPLETE");return incomplete;
            }
        }
        if (operation.equals("GET_TABLE_DDL")) matches = matches.stream().map(table -> {
            var value = new LinkedHashMap<>(table);
            String dialect = SqlDatasourceConfigService.normalizeDatabaseTypeToken(asset.getDatabaseType());
            String quote = Set.of("mysql", "hive", "spark").contains(dialect) ? "`" : "\"";
            java.util.function.Function<String,String> identifier = name -> quote + name.replace(quote,quote+quote) + quote;
            @SuppressWarnings("unchecked") var columns = (List<Map<String,Object>>)table.get("columns");
            String qualified = (table.get("schema") == null ? "" : identifier.apply(table.get("schema").toString()) + ".") + identifier.apply(table.get("tableName").toString());
            value.put("ddl", "CREATE TABLE " + qualified + " (\n" + columns.stream().map(column -> "  " + identifier.apply(column.get("name").toString()) + " "
                + Objects.toString(column.get("columnType"),Objects.toString(column.get("dataType"),"UNKNOWN")))
                .collect(Collectors.joining(",\n")) + "\n);");
            value.put("ddlSource", "GENERATED_FROM_AUTHORIZED_METADATA");
            value.put("ddlScope", "VISIBLE_COLUMNS_ONLY; ORIGINAL_DDL_AND_UNCOLLECTED_CONSTRAINTS_NOT_INCLUDED");
            return (Map<String,Object>)value;
        }).toList();
        if (Boolean.FALSE.equals(arguments.get("includeColumns"))) matches = matches.stream().map(item -> {
            Map<String, Object> lightweight = new LinkedHashMap<>(item); lightweight.remove("columns"); return lightweight;
        }).toList();
        return result(arguments, matches, snapshot, List.of(provenance), page, size, "SUCCEEDED");
    }

    private boolean sameEvidence(Map<String,Object> authorized,Map<String,Object> indexed){
        return authorized.entrySet().stream().allMatch(e->Objects.equals(e.getValue(),indexed.get(e.getKey())));
    }
    @SuppressWarnings("unchecked") private Set<String> columnNames(Map<String,Object> table){
        return ((List<Map<String,Object>>)table.getOrDefault("columns",List.of())).stream().map(c->c.get("name").toString()).collect(Collectors.toCollection(LinkedHashSet::new));
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
