package com.chatchat.runtime.market.storage;

import com.chatchat.runtime.market.config.MarketModuleProperties;
import com.chatchat.runtime.market.model.MarketObservation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Owns schema registration, safe DDL evolution and bounded reads of structured financial observations. */
@Service
public class FinancialDataStore {
    private static final Logger log = LoggerFactory.getLogger(FinancialDataStore.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> RESERVED = Set.of("key", "value", "date", "year", "month", "order", "group", "rank");
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final MarketModuleProperties properties;
    private boolean mysql;

    public FinancialDataStore(JdbcTemplate jdbc, DataSource dataSource, ObjectMapper mapper,
                              MarketModuleProperties runtimeProperties) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.mapper = mapper;
        this.properties = runtimeProperties;
    }

    @PostConstruct
    public void initialize() throws Exception {
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            mysql = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
        }
        jdbc.execute("create table if not exists market_asset_catalog ("
            + identity("id") + " primary key, dataset_code varchar(64) not null, asset_name varchar(160) not null, "
            + "business_description varchar(4000) not null, business_tags_json varchar(4000) not null, "
            + "database_name varchar(128) not null, table_name varchar(128) not null, update_frequency varchar(128), "
            + "source_names_json varchar(4000), last_observation_date date, last_collected_at timestamp, "
            + "archive_table_name varchar(128), hot_retention_days integer, archive_retention_days integer, "
            + "history_granularity varchar(64), "
            + "created_at timestamp not null, updated_at timestamp not null, constraint uk_market_asset_code unique(dataset_code))");
        ensureCatalogColumn("archive_table_name", "varchar(128)");
        ensureCatalogColumn("hot_retention_days", "integer");
        ensureCatalogColumn("archive_retention_days", "integer");
        ensureCatalogColumn("history_granularity", "varchar(64)");
        jdbc.update("update market_asset_catalog set archive_table_name=concat(table_name,'_weekly_snapshot') "
            + "where archive_table_name is null or archive_table_name='' ");
        jdbc.update("update market_asset_catalog set hot_retention_days=? where hot_retention_days is null", hotDays());
        jdbc.update("update market_asset_catalog set archive_retention_days=? where archive_retention_days is null", archiveDays());
        jdbc.update("update market_asset_catalog set history_granularity='DAILY_7D_WEEKLY' "
            + "where history_granularity is null or history_granularity='' ");
        jdbc.execute("create table if not exists data_schema_registry ("
            + identity("id") + " primary key, dataset_code varchar(64) not null, table_name varchar(128) not null, "
            + "field_name varchar(128) not null, source_field varchar(128) not null, field_type varchar(32) not null, "
            + "business_description varchar(1000), schema_version integer not null, created_at timestamp not null, "
            + "updated_at timestamp not null, constraint uk_data_schema_field unique(dataset_code, field_name))");
        ensureIndex("data_schema_registry", "idx_data_schema_dataset", "dataset_code");
    }

    public synchronized StoredObservation store(MarketObservation item) {
        List<StoredObservation> stored = storeAll(item == null ? List.of() : List.of(item));
        return stored.isEmpty() ? null : stored.get(0);
    }

    /** Ensures a page schema once, then writes every observation with the same idempotent upsert semantics. */
    public synchronized List<StoredObservation> storeAll(Collection<MarketObservation> items) {
        if (!properties.isEnabled() || items == null || items.isEmpty()) return List.of();
        try {
            LocalDate collectedDate = LocalDate.now(SHANGHAI);
            FinancialDatasetDefinition definition = null;
            Map<String, ColumnValue> pageColumns = new LinkedHashMap<>();
            List<PreparedObservation> prepared = new ArrayList<>();
            for (MarketObservation item : items) {
                if (item == null || item.metadata() == null) continue;
                FinancialDatasetDefinition current = FinancialDatasetDefinition.from(item.metadata());
                if (current == null) continue;
                if (definition == null) definition = current;
                if (!definition.code().equals(current.code())) {
                    throw new IllegalArgumentException("A financial write batch cannot mix datasets");
                }
                LocalDate observationDate = observationDate(item, collectedDate);
                Map<String, Object> payload = payload(item);
                Map<String, ColumnValue> columns = columns(payload);
                for (ColumnValue column : columns.values()) pageColumns.merge(column.name(), column,
                    (left, right) -> left.type() == right.type() ? left
                        : new ColumnValue(left.name(), left.sourceName(), FieldType.STRING, left.value()));
                String identity = item.sourceUrl() == null || item.sourceUrl().isBlank()
                    ? item.title() + "|" + observationDate + "|" + mapper.writeValueAsString(payload) : item.sourceUrl();
                String recordKey = sha256(current.code() + "|" + item.source().id() + "|" + identity);
                prepared.add(new PreparedObservation(item, observationDate, payload, columns, recordKey));
            }
            if (definition == null || prepared.isEmpty()) return List.of();
            ensureTable(definition, pageColumns.values());
            PreparedObservation latest = prepared.stream()
                .max(Comparator.comparing(PreparedObservation::observationDate)).orElseThrow();
            upsertCatalog(definition, latest.item(), latest.observationDate());
            List<StoredObservation> stored = new ArrayList<>(prepared.size());
            for (PreparedObservation value : prepared) {
                write(definition.tableName(), collectedDate, value.observationDate(), value.item(), value.recordKey(),
                    value.payload(), value.columns());
                stored.add(new StoredObservation(definition.code(), definition.tableName(), value.recordKey(),
                    value.observationDate(), collectedDate));
            }
            return List.copyOf(stored);
        } catch (Exception ex) {
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            throw new IllegalStateException("Failed to persist financial dataset batch: " + root.getMessage(), ex);
        }
    }

    public List<Map<String, Object>> searchCatalog(String query, int limit) {
        int bounded = Math.max(1, Math.min(limit, 50));
        String term = query == null ? "" : query.trim();
        if (term.isBlank()) {
            return jdbc.queryForList("select dataset_code,asset_name,business_description,business_tags_json,database_name,"
                + "table_name,archive_table_name,hot_retention_days,archive_retention_days,history_granularity,"
                + "update_frequency,last_observation_date,last_collected_at from market_asset_catalog order by updated_at desc limit ?", bounded);
        }
        String like = "%" + term.toLowerCase(Locale.ROOT) + "%";
        return jdbc.queryForList("select dataset_code,asset_name,business_description,business_tags_json,database_name,"
            + "table_name,archive_table_name,hot_retention_days,archive_retention_days,history_granularity,"
            + "update_frequency,last_observation_date,last_collected_at from market_asset_catalog "
            + "where lower(asset_name) like ? or lower(business_description) like ? or lower(business_tags_json) like ? "
            + "or lower(dataset_code) like ? order by updated_at desc limit ?", like, like, like, like, bounded);
    }

    public List<String> catalogCodes() {
        return jdbc.queryForList("select dataset_code from market_asset_catalog order by dataset_code", String.class);
    }

    public Map<String, Object> query(String datasetCode, Map<String, Object> filters,
                                     LocalDate startDate, LocalDate endDate, int requestedLimit) {
        return query(datasetCode, filters, startDate, endDate, requestedLimit, "auto");
    }

    /** Queries daily hot rows, weekly history, or both according to the requested time range. */
    public Map<String, Object> query(String datasetCode, Map<String, Object> filters,
                                     LocalDate startDate, LocalDate endDate, int requestedLimit,
                                     String requestedHistoryMode) {
        long startedAt = System.currentTimeMillis();
        String code = FinancialDatasetDefinition.normalizeCode(datasetCode);
        Map<String, Object> asset = catalog(code);
        if (asset.isEmpty()) throw new IllegalArgumentException("Unknown financial dataset: " + datasetCode);
        String table = identifier(String.valueOf(asset.get("table_name")));
        Object archiveValue = asset.get("archive_table_name");
        String archiveTable = archiveValue == null || String.valueOf(archiveValue).isBlank()
            ? archiveTable(table) : identifier(String.valueOf(archiveValue));
        Map<String, String> allowed = schemaFields(code);
        int limit = Math.max(1, Math.min(requestedLimit <= 0 ? properties.getDefaultQueryLimit() : requestedLimit,
            properties.getMaxQueryLimit()));
        String mode = normalizeHistoryMode(requestedHistoryMode);
        log.info("Financial data query started dataset={} table={} filters={} startDate={} endDate={} historyMode={} limit={}",
            code, table, filters == null ? Map.of() : filters, startDate, endDate, mode, limit);
        LocalDate cutoff = LocalDate.now(SHANGHAI).minusDays(hotDays());
        boolean readWeekly = "weekly".equals(mode) || ("auto".equals(mode) && startDate != null && startDate.isBefore(cutoff));
        boolean readDaily = "daily".equals(mode) || ("auto".equals(mode) && (endDate == null || !endDate.isBefore(cutoff)));
        List<Map<String, Object>> combined = new ArrayList<>();
        List<String> tiers = new ArrayList<>();
        if (readWeekly && tableExists(archiveTable)) {
            combined.addAll(queryTable(archiveTable, code, allowed, filters, startDate, endDate, limit, "weekly_snapshot"));
            tiers.add("weekly_snapshot");
        }
        if (readDaily) {
            LocalDate hotStart = "auto".equals(mode) && startDate != null && startDate.isBefore(cutoff) ? cutoff : startDate;
            combined.addAll(queryTable(table, code, allowed, filters, hotStart, endDate, limit, "daily_hot"));
            tiers.add("daily_hot");
        }
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> row : combined) {
            String key = String.valueOf(row.get("record_key")) + "|" + row.get("observation_date") + "|" + row.get("_storage_tier");
            unique.putIfAbsent(key, row);
        }
        List<Map<String, Object>> rows = unique.values().stream()
            .sorted(Comparator.comparing(this::rowSortKey).reversed()).limit(limit).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataset", code);
        result.put("asset", asset);
        result.put("history_mode", mode);
        result.put("storage_tiers", tiers);
        result.put("count", rows.size());
        result.put("rows", rows);
        result.put("retention_policy", retentionPolicy());
        result.put("query_constraints", Map.of(
            "max_rows", properties.getMaxQueryLimit(),
            "read_only", true,
            "filter_operators", List.of("exact", "Like")));
        log.info("Financial data query completed dataset={} table={} storageTiers={} returnedRows={} durationMs={}",
            code, table, tiers, rows.size(), System.currentTimeMillis() - startedAt);
        return result;
    }

    /** Archives one representative trading-day snapshot per completed week, then removes expired hot rows. */
    public synchronized RetentionRunResult archiveAndCleanup() {
        return archiveAndCleanup(LocalDate.now(SHANGHAI));
    }

    RetentionRunResult archiveAndCleanup(LocalDate today) {
        if (!properties.isEnabled() || !properties.getRetention().isEnabled()) {
            return new RetentionRunResult(today, 0, 0, 0, List.of());
        }
        LocalDate hotCutoff = today.minusDays(hotDays());
        LocalDate archiveCutoff = today.minusDays(archiveDays());
        int archived = 0;
        int deleted = 0;
        int pruned = 0;
        List<Map<String, Object>> datasets = new ArrayList<>();
        for (Map<String, Object> catalog : jdbc.queryForList("select dataset_code,table_name from market_asset_catalog order by dataset_code")) {
            String code = String.valueOf(catalog.get("dataset_code"));
            String table = identifier(String.valueOf(catalog.get("table_name")));
            String archive = archiveTable(table);
            try {
                List<LocalDate> oldDates = jdbc.queryForList("select distinct observation_date from `" + table
                    + "` where observation_date<? order by observation_date", LocalDate.class, Date.valueOf(hotCutoff));
                Map<LocalDate, LocalDate> latestByWeek = new LinkedHashMap<>();
                for (LocalDate date : oldDates) {
                    LocalDate week = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                    latestByWeek.merge(week, date, (left, right) -> right.isAfter(left) ? right : left);
                }
                int datasetArchived = 0;
                if (!latestByWeek.isEmpty()) {
                    ensureArchiveTable(table, archive);
                    List<String> columns = tableColumns(table);
                    String names = columns.stream().map(name -> "`" + name + "`").collect(Collectors.joining(","));
                    for (Map.Entry<LocalDate, LocalDate> snapshot : latestByWeek.entrySet()) {
                        jdbc.update("delete from `" + archive + "` where snapshot_week=?", Date.valueOf(snapshot.getKey()));
                        datasetArchived += jdbc.update("insert into `" + archive + "`(" + names
                                + ",snapshot_week,archived_at) select " + names + ",?,? from `" + table
                                + "` where observation_date=?", Date.valueOf(snapshot.getKey()), Timestamp.from(Instant.now()),
                            Date.valueOf(snapshot.getValue()));
                    }
                }
                int datasetDeleted = jdbc.update("delete from `" + table + "` where observation_date<?", Date.valueOf(hotCutoff));
                int datasetPruned = tableExists(archive)
                    ? jdbc.update("delete from `" + archive + "` where snapshot_week<?", Date.valueOf(archiveCutoff)) : 0;
                archived += datasetArchived;
                deleted += datasetDeleted;
                pruned += datasetPruned;
                datasets.add(Map.of("dataset", code, "archived_rows", datasetArchived,
                    "deleted_hot_rows", datasetDeleted, "pruned_archive_rows", datasetPruned));
            } catch (Exception ex) {
                datasets.add(Map.of("dataset", code, "error", String.valueOf(ex.getMessage())));
            }
        }
        return new RetentionRunResult(today, archived, deleted, pruned, List.copyOf(datasets));
    }

    public Map<String, Object> catalog(String code) {
        List<Map<String, Object>> found = jdbc.queryForList("select * from market_asset_catalog where dataset_code=?", code);
        if (found.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>(found.get(0));
        result.put("fields", jdbc.queryForList("select field_name,source_field,field_type,business_description,schema_version "
            + "from data_schema_registry where dataset_code=? order by id", code));
        return result;
    }

    private void ensureTable(FinancialDatasetDefinition definition, Collection<ColumnValue> columns) {
        String table = identifier(definition.tableName());
        String primary = mysql ? "primary key(id,collected_date), unique key uk_" + table + "_record(record_key,collected_date)"
            : "primary key(id), constraint uk_" + table + "_record unique(record_key)";
        String ddl = "create table if not exists `" + table + "` (" + identity("id")
            + ", collected_date date not null, observation_date date not null, collected_at timestamp not null, "
            + "source_id bigint not null, source_code varchar(64) not null, source_url varchar(2000) not null, "
            + "record_key varchar(64) not null, payload_json " + textType() + " not null, " + primary + ")";
        if (mysql) ddl += " partition by hash(to_days(collected_date)) partitions " + Math.max(2, properties.getPartitionCount());
        jdbc.execute(ddl);
        ensureIndex(table, "idx_" + table + "_collected_date", "collected_date,observation_date");
        ensureIndex(table, indexName("idx_" + table + "_observation_date"), "observation_date");
        Map<String, String> existing = schemaFields(definition.code());
        if (mysql) migrateLegacyStringColumns(table, existing);
        int version = existing.isEmpty() ? 1 : jdbc.queryForObject(
            "select coalesce(max(schema_version),1) from data_schema_registry where dataset_code=?", Integer.class, definition.code());
        for (ColumnValue column : columns) {
            if (!existing.containsKey(column.name())) {
                jdbc.execute("alter table `" + table + "` add column `" + column.name() + "` " + sqlType(column.type()));
                version++;
                registerField(definition, column, version);
            } else {
                FieldType registeredType = FieldType.valueOf(existing.get(column.name()));
                if (requiresTextWidening(registeredType, column.type())) {
                    widenToText(table, column.name());
                    registeredType = FieldType.STRING;
                    existing.put(column.name(), registeredType.name());
                    version++;
                }
                registerField(definition, new ColumnValue(column.name(), column.sourceName(),
                    registeredType, column.value()), version);
            }
        }
    }

    private boolean requiresTextWidening(FieldType registered, FieldType incoming) {
        return registered != incoming && registered != FieldType.STRING && registered != FieldType.JSON;
    }

    private void widenToText(String table, String column) {
        String ddl = mysql
            ? "alter table `" + table + "` modify column `" + column + "` longtext"
            : "alter table `" + table + "` alter column `" + column + "` clob";
        jdbc.execute(ddl);
    }

    private void migrateLegacyStringColumns(String table, Map<String, String> registeredFields) {
        List<String> varcharColumns = jdbc.queryForList(
            "select column_name from information_schema.columns where table_schema=database() "
                + "and table_name=? and data_type in ('varchar','char')", String.class, table);
        for (String column : varcharColumns) {
            if (!FieldType.STRING.name().equals(registeredFields.get(column))) continue;
            jdbc.execute("alter table `" + table + "` modify column `" + column + "` longtext");
        }
    }

    private void registerField(FinancialDatasetDefinition definition, ColumnValue field, int version) {
        String description = definition.fieldDescriptions().getOrDefault(field.name(), humanize(field.sourceName()));
        int updated = jdbc.update("update data_schema_registry set field_type=?,business_description=?,schema_version=?,updated_at=? "
                + "where dataset_code=? and field_name=?", field.type().name(), description, version,
            java.sql.Timestamp.from(Instant.now()), definition.code(), field.name());
        if (updated == 0) jdbc.update("insert into data_schema_registry(dataset_code,table_name,field_name,source_field,field_type,"
                + "business_description,schema_version,created_at,updated_at) values(?,?,?,?,?,?,?,?,?)",
            definition.code(), definition.tableName(), field.name(), field.sourceName(), field.type().name(), description,
            version, java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
    }

    private void write(String rawTable, LocalDate collectedDate, LocalDate observationDate, MarketObservation item,
                       String recordKey, Map<String, Object> payload, Map<String, ColumnValue> columns) throws JsonProcessingException {
        String table = identifier(rawTable);
        List<String> names = new ArrayList<>(List.of("collected_date", "observation_date", "collected_at", "source_id",
            "source_code", "source_url", "record_key", "payload_json"));
        List<Object> values = new ArrayList<>(List.of(Date.valueOf(collectedDate), Date.valueOf(observationDate),
            java.sql.Timestamp.from(Instant.now()), item.source().id(), item.source().code(),
            item.sourceUrl() == null ? item.source().entryUrl() : item.sourceUrl(), recordKey,
            mapper.writeValueAsString(payload)));
        columns.values().stream().sorted(Comparator.comparing(ColumnValue::name)).forEach(column -> {
            names.add(column.name()); values.add(sqlValue(column));
        });
        String fields = names.stream().map(name -> "`" + name + "`").collect(Collectors.joining(","));
        String placeholders = names.stream().map(ignored -> "?").collect(Collectors.joining(","));
        if (mysql) {
            String updates = names.stream().filter(name -> !Set.of("record_key", "collected_date").contains(name))
                .map(name -> "`" + name + "`=values(`" + name + "`)").collect(Collectors.joining(","));
            jdbc.update("insert into `" + table + "`(" + fields + ") values(" + placeholders + ") on duplicate key update " + updates,
                values.toArray());
        } else {
            jdbc.update("merge into `" + table + "`(" + fields + ") key(record_key) values(" + placeholders + ")", values.toArray());
        }
    }

    private void upsertCatalog(FinancialDatasetDefinition definition, MarketObservation item, LocalDate observationDate)
        throws JsonProcessingException {
        String database = databaseName();
        String sources = mapper.writeValueAsString(List.of(item.source().name()));
        String tags = mapper.writeValueAsString(definition.keywords());
        Instant now = Instant.now();
        int updated = jdbc.update("update market_asset_catalog set asset_name=?,business_description=?,business_tags_json=?,"
                + "database_name=?,table_name=?,archive_table_name=?,hot_retention_days=?,archive_retention_days=?,history_granularity=?,"
                + "update_frequency=?,source_names_json=?,last_observation_date=?,last_collected_at=?,updated_at=? "
                + "where dataset_code=?", definition.name(), definition.description(), tags, database, definition.tableName(),
            archiveTable(definition.tableName()), hotDays(), archiveDays(), "DAILY_7D_WEEKLY", definition.updateFrequency(),
            sources, Date.valueOf(observationDate), java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now), definition.code());
        if (updated == 0) jdbc.update("insert into market_asset_catalog(dataset_code,asset_name,business_description,business_tags_json,"
                + "database_name,table_name,archive_table_name,hot_retention_days,archive_retention_days,history_granularity,"
                + "update_frequency,source_names_json,last_observation_date,last_collected_at,created_at,updated_at) "
                + "values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", definition.code(), definition.name(), definition.description(), tags,
            database, definition.tableName(), archiveTable(definition.tableName()), hotDays(), archiveDays(), "DAILY_7D_WEEKLY",
            definition.updateFrequency(), sources, Date.valueOf(observationDate),
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
    }

    private List<Map<String, Object>> queryTable(String table, String code, Map<String, String> allowed,
                                                  Map<String, Object> filters, LocalDate startDate,
                                                  LocalDate endDate, int limit, String tier) {
        StringBuilder sql = new StringBuilder("select * from `").append(table).append("` where 1=1");
        List<Object> args = new ArrayList<>();
        if (startDate != null) { sql.append(" and observation_date>=?"); args.add(Date.valueOf(startDate)); }
        if (endDate != null) { sql.append(" and observation_date<=?"); args.add(Date.valueOf(endDate)); }
        if (filters != null) for (Map.Entry<String, Object> entry : filters.entrySet()) {
            FilterField filter = filterField(entry.getKey());
            String field = filter.field();
            boolean governed = allowed.containsKey(field) || Set.of("source_id", "observation_date", "collected_date").contains(field)
                || ("weekly_snapshot".equals(tier) && "snapshot_week".equals(field));
            if (!governed) {
                throw new IllegalArgumentException("Filter field is not registered for " + code + ": " + entry.getKey());
            }
            if (filter.like()) {
                if (!"STRING".equalsIgnoreCase(allowed.get(field))) {
                    throw new IllegalArgumentException("Like filter requires a registered string field for "
                        + code + ": " + entry.getKey());
                }
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim();
                if (value.isBlank()) throw new IllegalArgumentException("Like filter value cannot be blank: " + entry.getKey());
                if (value.length() > 160) throw new IllegalArgumentException("Like filter value is too long: " + entry.getKey());
                sql.append(" and lower(`").append(field).append("`) like ?");
                args.add("%" + value.toLowerCase(Locale.ROOT) + "%");
            } else {
                sql.append(" and `").append(field).append("`=?");
                args.add(entry.getValue());
            }
        }
        sql.append(" order by observation_date desc,collected_at desc limit ?");
        args.add(limit);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        return rows.stream().map(row -> { Map<String, Object> value = new LinkedHashMap<>(row);
            value.put("_storage_tier", tier); return value; }).toList();
    }

    private void ensureArchiveTable(String sourceTable, String archiveTable) {
        if (!tableExists(archiveTable)) {
            jdbc.execute("create table `" + archiveTable + "` as select * from `" + sourceTable + "` where 1=0");
        }
        Set<String> columns = Set.copyOf(tableColumns(archiveTable));
        if (!columns.contains("snapshot_week")) jdbc.execute("alter table `" + archiveTable + "` add column snapshot_week date");
        if (!columns.contains("archived_at")) jdbc.execute("alter table `" + archiveTable + "` add column archived_at timestamp");
        ensureIndex(archiveTable, indexName("idx_" + archiveTable + "_week"), "snapshot_week,observation_date");
    }

    private void ensureCatalogColumn(String column, String type) {
        if (!tableColumns("market_asset_catalog").contains(column)) {
            jdbc.execute("alter table market_asset_catalog add column " + column + " " + type);
        }
    }

    private List<String> tableColumns(String table) {
        return jdbc.query("select * from `" + identifier(table) + "` where 1=0", rs -> {
            List<String> result = new ArrayList<>();
            var metadata = rs.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) result.add(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT));
            return result;
        });
    }

    private boolean tableExists(String table) {
        try (var connection = dataSource.getConnection(); var found = connection.getMetaData()
            .getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
            if (found.next()) return true;
        } catch (Exception ignored) { }
        try { jdbc.queryForObject("select count(*) from `" + identifier(table) + "` where 1=0", Integer.class); return true; }
        catch (Exception ignored) { return false; }
    }

    private String archiveTable(String table) { return identifier(table + "_weekly_snapshot"); }
    private int hotDays() { return Math.max(1, properties.getRetention().getHotDays()); }
    private int archiveDays() { return Math.max(hotDays(), properties.getRetention().getWeeklyArchiveDays()); }
    private Map<String, Object> retentionPolicy() {
        return Map.of("daily_hot_days", hotDays(), "weekly_snapshot_days", archiveDays(),
            "weekly_snapshot_schedule", properties.getRetention().getCron(), "timezone", properties.getRetention().getZoneId());
    }
    private String normalizeHistoryMode(String value) {
        String mode = value == null || value.isBlank() ? "auto" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("auto", "daily", "weekly").contains(mode)) {
            throw new IllegalArgumentException("historyMode must be auto, daily, or weekly");
        }
        return mode;
    }
    private String rowSortKey(Map<String, Object> row) {
        return String.valueOf(row.getOrDefault("observation_date", "")) + "|"
            + String.valueOf(row.getOrDefault("collected_at", ""));
    }

    private FilterField filterField(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        boolean like = name.endsWith("Like") || name.toLowerCase(Locale.ROOT).endsWith("_like");
        if (like) name = name.endsWith("Like") ? name.substring(0, name.length() - 4)
            : name.substring(0, name.length() - 5);
        if (name.isBlank()) throw new IllegalArgumentException("Filter field cannot be blank");
        return new FilterField(columnName(name), like);
    }
    private String indexName(String value) { return value.length() <= 64 ? value : value.substring(0, 64); }

    private Map<String, Object> payload(MarketObservation item) {
        Map<String, Object> payload = new LinkedHashMap<>(item.metadata());
        payload.put("title", item.title());
        payload.put("summary", item.summary());
        payload.put("category", item.categories() == null || item.categories().isEmpty() ? "" : item.categories().get(0));
        return payload;
    }

    private Map<String, ColumnValue> columns(Map<String, Object> payload) {
        Map<String, ColumnValue> result = new LinkedHashMap<>();
        payload.forEach((source, raw) -> {
            if (raw == null || Set.of("legalDisclaimer", "attachmentUrls", "attachmentAllowedDomains").contains(source)) return;
            String name = columnName(source);
            if (Set.of("id", "collected_date", "observation_date", "collected_at", "source_id", "source_code",
                "source_url", "record_key", "payload_json").contains(name)) name = "data_" + name;
            Object value = normalizeValue(source, raw);
            FieldType type = value instanceof Boolean ? FieldType.BOOLEAN
                : value instanceof BigDecimal || value instanceof Number ? FieldType.DECIMAL
                : value instanceof LocalDate ? FieldType.DATE
                : raw instanceof Map<?, ?> || raw instanceof Collection<?> ? FieldType.JSON : FieldType.STRING;
            ColumnValue previous = result.putIfAbsent(name, new ColumnValue(name, source, type, value));
            if (previous != null && !previous.sourceName().equals(source)) {
                throw new IllegalArgumentException("Financial API fields collide after normalization: "
                    + previous.sourceName() + " and " + source);
            }
        });
        return result;
    }

    private Object normalizeValue(String field, Object raw) {
        if (raw instanceof Map<?, ?> || raw instanceof Collection<?>) {
            try { return mapper.writeValueAsString(raw); } catch (JsonProcessingException ex) { return String.valueOf(raw); }
        }
        if (raw instanceof Number || raw instanceof Boolean || raw instanceof LocalDate) return raw;
        String value = String.valueOf(raw).trim();
        if (field.toLowerCase(Locale.ROOT).endsWith("date")) {
            try { return LocalDate.parse(value.substring(0, Math.min(10, value.length()))); } catch (Exception ignored) { }
        }
        if (field.matches("(?i).*(amount|balance|price|close|open|high|low|volume|value|ratio|pct|shares|size|pe|pb|cap|yield|rate|count|number|total|vol).*")
            && value.replace(",", "").matches("[-+]?[0-9]+([.][0-9]+)?")) {
            try { return new BigDecimal(value.replace(",", "")); } catch (NumberFormatException ignored) { }
        }
        return value;
    }

    private Object sqlValue(ColumnValue column) {
        if (column.value() instanceof LocalDate date) return Date.valueOf(date);
        return column.value();
    }

    private LocalDate observationDate(MarketObservation item, LocalDate fallback) {
        for (String key : List.of("tradeDate", "scaleDate", "exDate", "statisticDate", "date")) {
            Object value = item.metadata().get(key);
            if (value == null) continue;
            try { return LocalDate.parse(String.valueOf(value).trim().substring(0, 10)); } catch (Exception ignored) { }
        }
        return item.publishTime() == null ? fallback : item.publishTime().atZone(SHANGHAI).toLocalDate();
    }

    private Map<String, String> schemaFields(String code) {
        return jdbc.query("select field_name,field_type from data_schema_registry where dataset_code=?",
            rs -> { Map<String, String> result = new LinkedHashMap<>(); while (rs.next()) result.put(rs.getString(1), rs.getString(2)); return result; }, code);
    }

    private String databaseName() {
        try (var connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            return catalog == null || catalog.isBlank() ? connection.getMetaData().getDatabaseProductName() : catalog;
        } catch (Exception ex) { return "financial-data"; }
    }

    private void ensureIndex(String table, String index, String columns) {
        try (var connection = dataSource.getConnection(); var found = connection.getMetaData()
            .getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (found.next()) if (index.equalsIgnoreCase(found.getString("INDEX_NAME"))) return;
        } catch (Exception ignored) { }
        jdbc.execute("create index " + index + " on `" + identifier(table) + "`(" + columns + ")");
    }

    private String identity(String name) {
        return name + (mysql ? " bigint auto_increment" : " bigint generated by default as identity");
    }
    private String textType() { return mysql ? "longtext" : "clob"; }
    private String sqlType(FieldType type) {
        return switch (type) {
            case BOOLEAN -> "boolean";
            case DECIMAL -> "decimal(38,10)";
            case DATE -> "date";
            case JSON -> textType();
            case STRING -> textType();
        };
    }

    static String columnName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Empty field name");
        String snake = value.trim().replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").replaceAll("[^A-Za-z0-9_]+", "_")
            .replaceAll("_+", "_").replaceAll("^_|_$", "").toLowerCase(Locale.ROOT);
        if (snake.isBlank() || !Character.isLetter(snake.charAt(0))) snake = "f_" + snake;
        if (RESERVED.contains(snake)) snake = "data_" + snake;
        return snake.length() > 60 ? snake.substring(0, 60) : snake;
    }

    private String identifier(String value) {
        String normalized = FinancialDatasetDefinition.normalizeCode(value);
        if (!normalized.equals(value)) throw new IllegalArgumentException("Unsafe table identifier: " + value);
        return normalized;
    }

    private String humanize(String source) { return "来源字段 " + source; }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    enum FieldType { STRING, DECIMAL, BOOLEAN, DATE, JSON }
    record ColumnValue(String name, String sourceName, FieldType type, Object value) { }
    record FilterField(String field, boolean like) { }
    public record StoredObservation(String datasetCode, String tableName, String recordKey,
                                    LocalDate observationDate, LocalDate collectedDate) { }
    public record RetentionRunResult(LocalDate runDate, int archivedRows, int deletedHotRows,
                                     int prunedArchiveRows, List<Map<String, Object>> datasets) { }

    private record PreparedObservation(MarketObservation item, LocalDate observationDate,
                                       Map<String, Object> payload, Map<String, ColumnValue> columns,
                                       String recordKey) { }
}
