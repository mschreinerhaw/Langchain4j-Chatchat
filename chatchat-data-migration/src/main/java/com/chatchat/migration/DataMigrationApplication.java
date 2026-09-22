package com.chatchat.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Standalone, one-shot relational data transfer between matching ChatChat schemas. */
public final class DataMigrationApplication {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private DataMigrationApplication() { }

    public static void main(String[] args) {
        try {
            if (Arrays.asList(args).contains("--init-only")) {
                TargetBootstrap.initOnly(args);
                return;
            }
            if (Arrays.asList(args).contains("--export-file") || Arrays.asList(args).contains("--import-file")) {
                DataFileTransfer.execute(args);
                return;
            }
            Options options = Options.parse(args);
            if (options == null) {
                return;
            }
            new DataMigrationApplication().run(options);
        } catch (Exception exception) {
            System.err.println("Migration failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private void run(Options options) throws SQLException, IOException {
        Engine sourceEngine = options.direction.equals("mysql-to-postgresql") ? Engine.MYSQL : Engine.POSTGRESQL;
        Engine targetEngine = sourceEngine == Engine.MYSQL ? Engine.POSTGRESQL : Engine.MYSQL;
        String mysqlDatabase = setting("MYSQL_DATABASE", "live_runtime_" + options.module);
        String pgDatabase = setting("PGDATABASE", "live_runtime_" + options.module);
        String pgSchema = identifier(setting("PGSCHEMA", "public"));
        try (Connection source = connect(sourceEngine, options.module, mysqlDatabase, pgDatabase);
             Connection target = TargetBootstrap.connectTarget(targetEngine, options.module,
                     mysqlDatabase, pgDatabase, options.dryRun)) {
            if (target == null) {
                return;
            }
            String sourceDatabase = source.getCatalog();
            String targetDatabase = target.getCatalog();
            source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            source.setReadOnly(true);
            source.setAutoCommit(false);
            String sourceScope = sourceEngine == Engine.MYSQL ? sourceDatabase : pgSchema;
            String targetScope = targetEngine == Engine.MYSQL ? targetDatabase : pgSchema;
            if (TargetBootstrap.ensureTables(target, targetEngine, options.module, targetScope, options.dryRun)) {
                return;
            }
            target.setAutoCommit(false);
            try {
                Set<String> expectedTables = MigrationSchema.expectedTables(options.module);
                Set<String> sourceTables = MigrationSchema.selectTables(tables(source, sourceScope), expectedTables,
                        "Source database");
                MigrationSchema.selectTables(tables(target, targetScope), expectedTables, "Target database");
                Map<String, ColumnMigrationPlan.Plan> tablePlans = new LinkedHashMap<>();
                for (String table : new TreeSet<>(sourceTables)) {
                    LinkedHashMap<String, Column> sourceDefinition = columns(source, sourceEngine, sourceScope, table);
                    LinkedHashMap<String, Column> targetDefinition = columns(target, targetEngine, targetScope, table);
                    tablePlans.put(table, ColumnMigrationPlan.create(table, sourceDefinition.keySet(), targetDefinition));
                }
                List<String> order = parentFirst(sourceTables, dependencies(target, targetEngine, targetScope));
                List<String> populated = new ArrayList<>();
                for (String table : order) {
                    if (rowExists(target, targetEngine, targetScope, table)) {
                        populated.add(table);
                    }
                }
                System.out.printf("%s:%s -> %s:%s; module=%s; tables=%d%n", sourceEngine,
                        sourceDatabase, targetEngine, targetDatabase, options.module, order.size());
                if (options.dryRun) {
                    if (!populated.isEmpty()) {
                        System.out.println("Target has data in " + populated.size()
                                + " tables; a write run requires --replace-target.");
                    }
                    for (String table : order) {
                        System.out.printf("%s: source=%d%n", table, count(source, sourceEngine, sourceScope, table));
                    }
                    System.out.println("Dry run completed; target unchanged.");
                    return;
                }
                if (!populated.isEmpty() && !options.replaceTarget) {
                    throw new IllegalStateException("Target contains data (first table: " + populated.get(0)
                            + "); use --replace-target to overwrite it.");
                }
                if (options.replaceTarget) {
                    for (int index = order.size() - 1; index >= 0; index--) {
                        try (Statement statement = target.createStatement()) {
                            statement.executeUpdate("DELETE FROM " + qualified(targetEngine, targetScope, order.get(index)));
                        }
                    }
                }
                long total = 0;
                Instant migrationTime = Instant.now();
                for (String table : order) {
                    long rows = copyTable(source, target, sourceEngine, targetEngine, sourceScope, targetScope,
                            table, tablePlans.get(table), options, migrationTime);
                    total += rows;
                    System.out.printf("%s: %d rows%n", table, rows);
                }
                if (targetEngine == Engine.POSTGRESQL) {
                    resetSequences(target, targetScope, order);
                }
                TargetBootstrap.seedMissingRows(target, targetEngine, options.module, targetScope);
                target.commit();
                System.out.printf("Migration completed: %d rows across %d tables.%n", total, order.size());
            } catch (Exception exception) {
                target.rollback();
                throw exception;
            } finally {
                source.rollback();
            }
        }
    }

    static Connection connect(Engine engine, String module, String mysqlDatabase, String pgDatabase)
            throws SQLException, IOException {
        Properties properties = new Properties();
        String url;
        if (engine == Engine.MYSQL) {
            url = setting("MYSQL_URL", "jdbc:mysql://" + setting("MYSQL_HOST", "127.0.0.1") + ":"
                    + setting("MYSQL_PORT", "3306") + "/" + mysqlDatabase + "?useUnicode=true&characterEncoding=UTF-8");
            properties.setProperty("user", setting("MYSQL_USER", "chatchat_" + module));
            properties.setProperty("password", secret("MYSQL_PASSWORD", setting("MYSQL_PWD", "")));
        } else {
            url = setting("PG_URL", "jdbc:postgresql://" + setting("PGHOST", "127.0.0.1") + ":"
                    + setting("PGPORT", "5432") + "/" + pgDatabase);
            properties.setProperty("user", setting("PGUSER", "chatchat_" + module));
            properties.setProperty("password", secret("PGPASSWORD", ""));
            properties.setProperty("options", "-c timezone=UTC");
        }
        return DriverManager.getConnection(url, properties);
    }

    static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static String secret(String name, String fallback) throws IOException {
        String file = System.getenv(name + "_FILE");
        if (file != null && !file.isBlank()) {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).replaceFirst("[\\r\\n]+$", "");
        }
        return setting(name, fallback);
    }

    static String identifier(String name) {
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsupported SQL identifier: " + name);
        }
        return name;
    }

    static String quoted(Engine engine, String name) {
        return engine == Engine.MYSQL ? "`" + identifier(name) + "`" : "\"" + identifier(name) + "\"";
    }

    static String qualified(Engine engine, String scope, String table) {
        return engine == Engine.MYSQL ? quoted(engine, table)
                : quoted(engine, scope) + "." + quoted(engine, table);
    }

    static Set<String> tables(Connection connection, String scope) throws SQLException {
        Set<String> found = new TreeSet<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema=? AND table_type='BASE TABLE'")) {
            query.setString(1, scope);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    found.add(identifier(rows.getString(1)));
                }
            }
        }
        return found;
    }

    static LinkedHashMap<String, Column> columns(Connection connection, Engine engine, String scope,
            String table) throws SQLException {
        String generated = engine == Engine.MYSQL ? "extra" : "is_generated";
        LinkedHashMap<String, Column> result = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT column_name, data_type, " + generated
                + ", is_nullable, column_default FROM information_schema.columns "
                + "WHERE table_schema=? AND table_name=? ORDER BY ordinal_position")) {
            query.setString(1, scope);
            query.setString(2, table);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String marker = rows.getString(3);
                    boolean computed = engine == Engine.MYSQL ? marker != null && marker.toLowerCase().contains("generated")
                            : marker != null && !marker.equalsIgnoreCase("NEVER");
                    if (!computed) {
                        String name = identifier(rows.getString(1));
                        result.put(name, new Column(name, rows.getString(2).toLowerCase(),
                                "YES".equalsIgnoreCase(rows.getString(4)), rows.getString(5)));
                    }
                }
            }
        }
        return result;
    }

    static Map<String, Set<String>> dependencies(Connection connection, Engine engine, String scope)
            throws SQLException {
        Map<String, Set<String>> result = new HashMap<>();
        String sql = engine == Engine.MYSQL
                ? "SELECT table_name, referenced_table_name FROM information_schema.key_column_usage "
                        + "WHERE table_schema=? AND referenced_table_name IS NOT NULL"
                : "SELECT child.relname, parent.relname FROM pg_constraint fk "
                        + "JOIN pg_class child ON child.oid=fk.conrelid "
                        + "JOIN pg_namespace ns ON ns.oid=child.relnamespace "
                        + "JOIN pg_class parent ON parent.oid=fk.confrelid "
                        + "WHERE fk.contype='f' AND ns.nspname=?";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, scope);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String child = identifier(rows.getString(1));
                    String parent = identifier(rows.getString(2));
                    if (child.equals(parent)) {
                        throw new IllegalStateException("Self-referencing foreign key requires manual migration: " + child);
                    }
                    result.computeIfAbsent(child, ignored -> new HashSet<>()).add(parent);
                }
            }
        }
        return result;
    }

    static List<String> parentFirst(Set<String> tables, Map<String, Set<String>> dependencies) {
        Map<String, Set<String>> remaining = new HashMap<>();
        for (String table : tables) {
            Set<String> parents = new HashSet<>(dependencies.getOrDefault(table, Set.of()));
            parents.retainAll(tables);
            remaining.put(table, parents);
        }
        List<String> order = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<String> ready = remaining.entrySet().stream().filter(entry -> entry.getValue().isEmpty())
                    .map(Map.Entry::getKey).sorted().toList();
            if (ready.isEmpty()) {
                throw new IllegalStateException("Foreign-key cycle requires manual migration: " + remaining.keySet());
            }
            order.addAll(ready);
            ready.forEach(remaining::remove);
            remaining.values().forEach(parents -> parents.removeAll(ready));
        }
        return order;
    }

    static boolean rowExists(Connection connection, Engine engine, String scope, String table)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT 1 FROM " + qualified(engine, scope, table) + " LIMIT 1")) {
            return rows.next();
        }
    }

    static long count(Connection connection, Engine engine, String scope, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + qualified(engine, scope, table))) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static long copyTable(Connection source, Connection target, Engine sourceEngine, Engine targetEngine,
            String sourceScope, String targetScope, String table, ColumnMigrationPlan.Plan plan,
            Options options, Instant migrationTime) throws SQLException {
        List<Column> ordered = new ArrayList<>(plan.copied().values());
        if (ordered.isEmpty()) {
            throw new IllegalStateException("No transferable columns in " + table);
        }
        String sourceFields = String.join(", ", ordered.stream().map(column -> quoted(sourceEngine, column.name)).toList());
        List<Column> inserted = new ArrayList<>(ordered);
        inserted.addAll(plan.filled());
        String targetFields = String.join(", ", inserted.stream().map(column -> quoted(targetEngine, column.name)).toList());
        String placeholders = String.join(", ", java.util.Collections.nCopies(inserted.size(), "?"));
        String select = "SELECT " + sourceFields + " FROM " + qualified(sourceEngine, sourceScope, table);
        String insert = "INSERT INTO " + qualified(targetEngine, targetScope, table) + " (" + targetFields
                + ") VALUES (" + placeholders + ")";
        long copied = 0;
        try (PreparedStatement read = source.prepareStatement(select, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY);
             PreparedStatement write = target.prepareStatement(insert)) {
            read.setFetchSize(sourceEngine == Engine.MYSQL ? Integer.MIN_VALUE : options.batchSize);
            try (ResultSet rows = read.executeQuery()) {
                int pending = 0;
                while (rows.next()) {
                    for (int index = 0; index < ordered.size(); index++) {
                        bind(write, index + 1, rows, index + 1, sourceEngine, targetEngine,
                                ordered.get(index).type, options.mysqlZone);
                    }
                    for (int index = 0; index < plan.filled().size(); index++) {
                        ColumnMigrationPlan.bindSynthetic(write, ordered.size() + index + 1,
                                plan.filled().get(index), migrationTime, options.mysqlZone);
                    }
                    write.addBatch();
                    pending++;
                    copied++;
                    if (pending == options.batchSize) {
                        write.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    write.executeBatch();
                }
            }
        }
        long sourceCount = count(source, sourceEngine, sourceScope, table);
        long targetCount = count(target, targetEngine, targetScope, table);
        if (copied != sourceCount || copied != targetCount) {
            throw new IllegalStateException("Row-count mismatch in " + table + ": copied=" + copied
                    + ", source=" + sourceCount + ", target=" + targetCount);
        }
        return copied;
    }

    private static void bind(PreparedStatement target, int targetIndex, ResultSet source, int sourceIndex,
            Engine sourceEngine, Engine targetEngine, String targetType, ZoneId mysqlZone) throws SQLException {
        Object value = source.getObject(sourceIndex);
        if (value == null) {
            target.setObject(targetIndex, null);
        } else if (sourceEngine == Engine.MYSQL && targetType.equals("boolean")) {
            boolean enabled = value instanceof byte[] bytes ? bytes.length > 0 && bytes[bytes.length - 1] != 0
                    : value instanceof Number number ? number.intValue() != 0 : (Boolean) value;
            target.setBoolean(targetIndex, enabled);
        } else if (sourceEngine == Engine.POSTGRESQL && targetType.equals("bit") && value instanceof Boolean flag) {
            target.setBoolean(targetIndex, flag);
        } else if (sourceEngine == Engine.MYSQL && targetType.equals("timestamp with time zone")) {
            LocalDateTime local = source.getObject(sourceIndex, LocalDateTime.class);
            target.setObject(targetIndex, local.atZone(mysqlZone).toOffsetDateTime());
        } else if (sourceEngine == Engine.POSTGRESQL && targetType.equals("datetime")) {
            OffsetDateTime instant = source.getObject(sourceIndex, OffsetDateTime.class);
            target.setObject(targetIndex, instant.atZoneSameInstant(mysqlZone).toLocalDateTime());
        } else if (targetEngine == Engine.POSTGRESQL && (targetType.equals("json") || targetType.equals("jsonb"))) {
            target.setObject(targetIndex, source.getString(sourceIndex), Types.OTHER);
        } else if (sourceEngine == Engine.POSTGRESQL && value instanceof org.postgresql.util.PGobject json) {
            target.setString(targetIndex, json.getValue());
        } else {
            target.setObject(targetIndex, value);
        }
    }

    static void resetSequences(Connection target, String schema, List<String> tables) throws SQLException {
        for (String table : tables) {
            try (PreparedStatement columns = target.prepareStatement("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema=? AND table_name=? AND identity_generation IS NOT NULL")) {
                columns.setString(1, schema);
                columns.setString(2, table);
                try (ResultSet names = columns.executeQuery()) {
                    while (names.next()) {
                        String column = identifier(names.getString(1));
                        String qualified = qualified(Engine.POSTGRESQL, schema, table);
                        try (PreparedStatement sequence = target.prepareStatement("SELECT pg_get_serial_sequence(?, ?)")) {
                            sequence.setString(1, qualified);
                            sequence.setString(2, column);
                            try (ResultSet result = sequence.executeQuery()) {
                                result.next();
                                String sequenceName = result.getString(1);
                                if (sequenceName == null) {
                                    continue;
                                }
                                try (Statement maximumQuery = target.createStatement();
                                     ResultSet maximum = maximumQuery.executeQuery("SELECT MAX("
                                             + quoted(Engine.POSTGRESQL, column) + ") FROM " + qualified)) {
                                    maximum.next();
                                    long max = maximum.getLong(1);
                                    boolean hasRows = !maximum.wasNull();
                                    try (PreparedStatement reset = target.prepareStatement("SELECT setval(?::regclass, ?, ?)")) {
                                        reset.setString(1, sequenceName);
                                        reset.setLong(2, hasRows ? max : 1);
                                        reset.setBoolean(3, hasRows);
                                        reset.executeQuery().close();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    enum Engine { MYSQL, POSTGRESQL }

    record Column(String name, String type, boolean nullable, String defaultValue) {
        Column(String name, String type) {
            this(name, type, false, null);
        }
    }

    private record Options(String direction, String module, boolean replaceTarget, boolean dryRun, int batchSize,
                           ZoneId mysqlZone) {
        private static Options parse(String[] args) {
            if (Arrays.asList(args).contains("--help")) {
                System.out.println("Usage: java -jar chatchat-data-migration.jar --direction mysql-to-postgresql|postgresql-to-mysql "
                        + "--module api|mcp [--dry-run] [--replace-target] [--batch-size 500] "
                        + "[--mysql-timezone Asia/Shanghai]\n"
                        + "       java -jar chatchat-data-migration.jar --export-file FILE --engine mysql|postgresql --module api|mcp\n"
                        + "       java -jar chatchat-data-migration.jar --import-file FILE --engine mysql|postgresql --module api|mcp "
                        + "[--dry-run] [--replace-target] [--batch-size 500] [--mysql-timezone Asia/Shanghai]\n"
                        + "       java -jar chatchat-data-migration.jar --init-only --engine mysql|postgresql --module api|mcp "
                        + "[--dry-run]");
                return null;
            }
            Map<String, String> values = new HashMap<>();
            Set<String> flags = new HashSet<>();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (argument.equals("--dry-run") || argument.equals("--replace-target")) {
                    flags.add(argument);
                } else if (Set.of("--direction", "--module", "--batch-size", "--mysql-timezone").contains(argument)
                        && index + 1 < args.length) {
                    values.put(argument, args[++index]);
                } else {
                    throw new IllegalArgumentException("Unknown or incomplete argument: " + argument);
                }
            }
            String direction = values.get("--direction");
            String module = values.get("--module");
            if (direction == null || module == null
                    || !Set.of("mysql-to-postgresql", "postgresql-to-mysql").contains(direction)
                    || !Set.of("api", "mcp").contains(module)) {
                throw new IllegalArgumentException("Specify --direction mysql-to-postgresql|postgresql-to-mysql "
                        + "and --module api|mcp; use --help for details.");
            }
            int batchSize = Integer.parseInt(values.getOrDefault("--batch-size", "500"));
            if (batchSize < 1) {
                throw new IllegalArgumentException("--batch-size must be positive");
            }
            return new Options(direction, module, flags.contains("--replace-target"), flags.contains("--dry-run"),
                    batchSize, ZoneId.of(values.getOrDefault("--mysql-timezone", "Asia/Shanghai")));
        }
    }
}
