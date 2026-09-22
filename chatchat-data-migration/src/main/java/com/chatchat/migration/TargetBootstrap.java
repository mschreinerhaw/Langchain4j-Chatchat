package com.chatchat.migration;

import static com.chatchat.migration.DataMigrationApplication.connect;
import static com.chatchat.migration.DataMigrationApplication.identifier;
import static com.chatchat.migration.DataMigrationApplication.quoted;
import static com.chatchat.migration.DataMigrationApplication.secret;
import static com.chatchat.migration.DataMigrationApplication.setting;
import static com.chatchat.migration.DataMigrationApplication.tables;

import com.chatchat.migration.DataMigrationApplication.Engine;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates absent target database objects and inserts missing starter-data records. */
final class TargetBootstrap {
    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)^create\\s+table\\s+([`\"]?\\w+[`\"]?)\\s*\\(");
    private static final Pattern CREATE_INDEX = Pattern.compile("(?is)^create\\s+(?:unique\\s+)?index\\s+"
            + "(?:if\\s+not\\s+exists\\s+)?([`\"]?\\w+[`\"]?)\\s+on\\s+([`\"]?\\w+[`\"]?)");
    private static final Pattern ALTER_CONSTRAINT = Pattern.compile("(?is)^alter\\s+table\\s+"
            + "(?:if\\s+exists\\s+)?([`\"]?\\w+[`\"]?)\\s+add\\s+constraint\\s+([`\"]?\\w+[`\"]?)");
    private static final Pattern INSERT = Pattern.compile("(?is)^insert\\s+into\\s+([`\"]?\\w+[`\"]?)"
            + "\\s*\\(([^)]+)\\)\\s*values\\s*(.+)$");

    private TargetBootstrap() { }

    static void initOnly(String[] args) throws SQLException, IOException {
        Map<String, String> values = new HashMap<>();
        boolean dryRun = false;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (argument.equals("--init-only")) {
                continue;
            }
            if (argument.equals("--dry-run")) {
                dryRun = true;
            } else if (Set.of("--engine", "--module").contains(argument) && index + 1 < args.length) {
                values.put(argument, args[++index]);
            } else {
                throw new IllegalArgumentException("Unknown or incomplete initialization argument: " + argument);
            }
        }
        String engineName = values.get("--engine");
        String module = values.get("--module");
        if (engineName == null || module == null || !Set.of("mysql", "postgresql").contains(engineName)
                || !Set.of("api", "mcp").contains(module)) {
            throw new IllegalArgumentException("Specify --init-only --engine mysql|postgresql --module api|mcp");
        }
        Engine engine = Engine.valueOf(engineName.toUpperCase());
        try (Connection target = connectTarget(engine, module,
                setting("MYSQL_DATABASE", "live_runtime_" + module),
                setting("PGDATABASE", "live_runtime_" + module), dryRun)) {
            if (target == null) {
                return;
            }
            String scope = engine == Engine.MYSQL ? target.getCatalog()
                    : identifier(setting("PGSCHEMA", "public"));
            if (ensureTables(target, engine, module, scope, dryRun)) {
                return;
            }
            if (dryRun) {
                seedMissingRows(target, engine, module, scope, true);
                System.out.println("Initialization dry run completed; target unchanged.");
                return;
            }
            target.setAutoCommit(false);
            try {
                seedMissingRows(target, engine, module, scope, false);
                target.commit();
            } catch (SQLException | RuntimeException error) {
                target.rollback();
                throw error;
            }
            System.out.println("Target database initialization completed.");
        }
    }

    static Connection connectTarget(Engine engine, String module, String mysqlDatabase, String pgDatabase,
            boolean dryRun) throws SQLException, IOException {
        try {
            return connect(engine, module, mysqlDatabase, pgDatabase);
        } catch (SQLException missing) {
            boolean absent = missingDatabase(engine, missing);
            boolean mysqlAccessDenied = engine == Engine.MYSQL && missing.getErrorCode() == 1044;
            if (!absent && !mysqlAccessDenied) {
                throw missing;
            }
            String database = configuredDatabase(engine, engine == Engine.MYSQL ? mysqlDatabase : pgDatabase);
            if (mysqlAccessDenied) {
                try (Connection admin = adminConnection(engine, module)) {
                    absent = !databaseExists(admin, database);
                } catch (SQLException | IOException adminError) {
                    missing.addSuppressed(adminError);
                    throw missing;
                }
            }
            if (!absent) {
                throw missing;
            }
            if (dryRun) {
                System.out.println("Target database " + database
                        + " does not exist; write run will create it, its tables, and starter data.");
                return null;
            }
            createDatabase(engine, module, database);
            return connect(engine, module, mysqlDatabase, pgDatabase);
        }
    }

    private static boolean missingDatabase(Engine engine, SQLException error) {
        return engine == Engine.MYSQL ? error.getErrorCode() == 1049 : "3D000".equals(error.getSQLState());
    }

    static String configuredDatabase(Engine engine, String fallback) {
        String url = System.getenv(engine == Engine.MYSQL ? "MYSQL_URL" : "PG_URL");
        if (url == null || url.isBlank()) {
            return identifier(fallback);
        }
        int schemeEnd = url.indexOf("://");
        int pathStart = schemeEnd < 0 ? -1 : url.indexOf('/', schemeEnd + 3);
        int queryStart = pathStart < 0 ? -1 : url.indexOf('?', pathStart);
        int end = queryStart < 0 ? url.length() : queryStart;
        if (pathStart < 0 || pathStart + 1 >= end) {
            throw new IllegalArgumentException("Set a database name in the JDBC URL before automatic creation");
        }
        return identifier(url.substring(pathStart + 1, end));
    }

    private static void createDatabase(Engine engine, String module, String database) throws SQLException, IOException {
        try (Connection admin = adminConnection(engine, module);
             Statement statement = admin.createStatement()) {
            if (engine == Engine.MYSQL) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + quoted(engine, database)
                        + " CHARACTER SET utf8mb4");
            } else {
                String owner = identifier(setting("PGUSER", "chatchat_" + module));
                try {
                    statement.execute("CREATE DATABASE " + quoted(engine, database) + " OWNER "
                            + quoted(engine, owner) + " ENCODING 'UTF8'");
                } catch (SQLException concurrentCreation) {
                    if (!"42P04".equals(concurrentCreation.getSQLState())) {
                        throw concurrentCreation;
                    }
                }
            }
        }
        System.out.println("Created target database: " + database);
    }

    private static Connection adminConnection(Engine engine, String module) throws SQLException, IOException {
        Properties credentials = new Properties();
        String url;
        if (engine == Engine.MYSQL) {
            String explicit = System.getenv("MYSQL_ADMIN_URL");
            url = explicit == null || explicit.isBlank() ? adminUrl(engine) : explicit;
            credentials.setProperty("user", setting("MYSQL_ADMIN_USER", setting("MYSQL_USER", "chatchat_" + module)));
            credentials.setProperty("password", secret("MYSQL_ADMIN_PASSWORD",
                    secret("MYSQL_PASSWORD", setting("MYSQL_PWD", ""))));
        } else {
            String explicit = System.getenv("PG_ADMIN_URL");
            url = explicit == null || explicit.isBlank() ? adminUrl(engine) : explicit;
            credentials.setProperty("user", setting("PG_ADMIN_USER", setting("PGUSER", "chatchat_" + module)));
            credentials.setProperty("password", secret("PG_ADMIN_PASSWORD", secret("PGPASSWORD", "")));
        }
        return DriverManager.getConnection(url, credentials);
    }

    private static boolean databaseExists(Connection admin, String database) throws SQLException {
        try (PreparedStatement query = admin.prepareStatement(
                "SELECT 1 FROM information_schema.schemata WHERE schema_name=?")) {
            query.setString(1, database);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    static String adminUrl(Engine engine) {
        String configured = System.getenv(engine == Engine.MYSQL ? "MYSQL_URL" : "PG_URL");
        if (configured != null && !configured.isBlank()) {
            int schemeEnd = configured.indexOf("://");
            int pathStart = schemeEnd < 0 ? -1 : configured.indexOf('/', schemeEnd + 3);
            if (pathStart < 0) {
                throw new IllegalArgumentException("Set MYSQL_ADMIN_URL or PG_ADMIN_URL for this JDBC URL form");
            }
            int queryStart = configured.indexOf('?', pathStart);
            String query = queryStart < 0 ? "" : configured.substring(queryStart);
            return configured.substring(0, pathStart + 1)
                    + (engine == Engine.MYSQL ? "" : "postgres") + query;
        }
        if (engine == Engine.MYSQL) {
            return "jdbc:mysql://" + setting("MYSQL_HOST", "127.0.0.1") + ":"
                    + setting("MYSQL_PORT", "3306") + "/?useUnicode=true&characterEncoding=UTF-8";
        }
        return "jdbc:postgresql://" + setting("PGHOST", "127.0.0.1") + ":"
                + setting("PGPORT", "5432") + "/postgres";
    }

    /** Returns true when dry-run found missing objects and must stop before normal migration checks. */
    static boolean ensureTables(Connection target, Engine engine, String module, String schema, boolean dryRun)
            throws SQLException, IOException {
        boolean missingSchema = engine == Engine.POSTGRESQL && !schemaExists(target, schema);
        Set<String> expected = MigrationSchema.expectedTables(module);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(tables(target, schema));
        if (dryRun && (missingSchema || !missing.isEmpty())) {
            if (missingSchema) {
                System.out.println("Target schema missing; would create: " + schema);
            }
            System.out.println("Target tables missing; would create: " + missing);
            System.out.println("Write run will also insert missing starter-data records after copying source rows.");
            return true;
        }
        if (engine == Engine.POSTGRESQL) {
            if (missingSchema) {
                try (Statement statement = target.createStatement()) {
                    statement.execute("CREATE SCHEMA " + quoted(engine, schema));
                }
                System.out.println("Created target schema: " + schema);
            }
            try (Statement statement = target.createStatement()) {
                statement.execute("SET search_path TO " + quoted(engine, schema));
            }
        }
        if (missing.isEmpty()) {
            return false;
        }
        List<String> script = schemaStatements(engine, module);
        if (engine == Engine.POSTGRESQL) {
            target.setAutoCommit(false);
        }
        try {
            for (String sql : script) {
                Matcher create = CREATE_TABLE.matcher(sql);
                if (create.find() && missing.contains(unquote(create.group(1)))) {
                    execute(target, sql);
                    System.out.println("Created target table: " + unquote(create.group(1)));
                }
            }
            for (String sql : script) {
                Matcher index = CREATE_INDEX.matcher(sql);
                if (index.find() && expected.contains(unquote(index.group(2)))
                        && !indexExists(target, engine, schema, unquote(index.group(1)))) {
                    execute(target, sql);
                }
            }
            for (String sql : script) {
                Matcher constraint = ALTER_CONSTRAINT.matcher(sql);
                if (constraint.find() && expected.contains(unquote(constraint.group(1)))
                        && !constraintExists(target, schema, unquote(constraint.group(1)),
                                unquote(constraint.group(2)))) {
                    execute(target, sql);
                }
            }
            if (engine == Engine.POSTGRESQL && module.equals("api")) {
                for (String sql : splitSql(MigrationSchema.resourceSql("postgresql",
                        "chatchat-api-post-schema.sql"))) {
                    Matcher index = CREATE_INDEX.matcher(sql);
                    if (index.find() && !indexExists(target, engine, schema, unquote(index.group(1)))) {
                        execute(target, sql);
                    }
                }
            }
            if (engine == Engine.POSTGRESQL) {
                target.commit();
            }
        } catch (SQLException | RuntimeException error) {
            if (engine == Engine.POSTGRESQL) {
                target.rollback();
            }
            throw error;
        } finally {
            if (engine == Engine.POSTGRESQL) {
                target.setAutoCommit(true);
            }
        }
        return false;
    }

    static void seedMissingRows(Connection target, Engine engine, String module, String schema)
            throws SQLException, IOException {
        seedMissingRows(target, engine, module, schema, false);
    }

    private static void seedMissingRows(Connection target, Engine engine, String module, String schema,
            boolean dryRun)
            throws SQLException, IOException {
        String file = module.equals("api") ? "chatchat-api-securities-seed.sql"
                : "chatchat-mcp-securities-seed.sql";
        Set<String> expected = MigrationSchema.expectedTables(module);
        for (String sql : splitSql(MigrationSchema.resourceSql(engine.name().toLowerCase(), file))) {
            int inserted = 0;
            String table = null;
            for (SeedRow row : parseSeedRows(sql)) {
                table = row.table();
                if (!expected.contains(table)) {
                    throw new IllegalStateException("Starter-data table is outside module schema: " + table);
                }
                if (!seedIdExists(target, engine, schema, table, row.id())) {
                    if (!dryRun) {
                        execute(target, "INSERT INTO " + DataMigrationApplication.qualified(engine, schema, table)
                                + " (" + row.columns() + ") VALUES " + row.tuple());
                    }
                    inserted++;
                }
            }
            if (inserted > 0) {
                System.out.printf("%s %d missing starter-data rows in %s%n",
                        dryRun ? "Would initialize" : "Initialized", inserted, table);
            }
        }
    }

    static List<SeedRow> parseSeedRows(String sql) {
        Matcher insert = INSERT.matcher(sql);
        if (!insert.find()) {
            throw new IllegalStateException("Unsupported starter-data statement");
        }
        String table = unquote(insert.group(1));
        List<String> names = splitExpressions(insert.group(2));
        int idIndex = names.indexOf("id");
        if (idIndex < 0) {
            throw new IllegalStateException("Starter-data INSERT has no id column: " + table);
        }
        List<SeedRow> result = new ArrayList<>();
        for (String tuple : valueTuples(insert.group(3))) {
            List<String> values = splitExpressions(tuple.substring(1, tuple.length() - 1));
            if (values.size() != names.size()) {
                throw new IllegalStateException("Starter-data column/value count mismatch: " + table);
            }
            result.add(new SeedRow(table, insert.group(2), stringLiteral(values.get(idIndex)), tuple));
        }
        return result;
    }

    record SeedRow(String table, String columns, String id, String tuple) { }

    private static boolean seedIdExists(Connection target, Engine engine, String schema, String table, String id)
            throws SQLException {
        String query = "SELECT 1 FROM " + DataMigrationApplication.qualified(engine, schema, table)
                + " WHERE " + quoted(engine, "id") + "=? LIMIT 1";
        try (PreparedStatement statement = target.prepareStatement(query)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static String stringLiteral(String expression) {
        String value = expression.trim();
        if (value.length() < 2 || value.charAt(0) != '\'' || value.charAt(value.length() - 1) != '\'') {
            throw new IllegalStateException("Starter-data id must be a SQL string literal: " + value);
        }
        return value.substring(1, value.length() - 1).replace("''", "'");
    }

    /** Extract top-level VALUES tuples without splitting commas inside quoted text or expressions. */
    static List<String> valueTuples(String valuesSql) {
        List<String> tuples = new ArrayList<>();
        int depth = 0;
        int start = -1;
        char quote = 0;
        for (int index = 0; index < valuesSql.length(); index++) {
            char character = valuesSql.charAt(index);
            char next = index + 1 < valuesSql.length() ? valuesSql.charAt(index + 1) : 0;
            if (quote != 0) {
                if (character == '\\' && next != 0) {
                    index++;
                } else if (character == quote && next == quote) {
                    index++;
                } else if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"' || character == '`') {
                quote = character;
            } else if (character == '(') {
                if (depth++ == 0) {
                    start = index;
                }
            } else if (character == ')') {
                if (--depth == 0) {
                    tuples.add(valuesSql.substring(start, index + 1));
                    start = -1;
                }
                if (depth < 0) {
                    throw new IllegalStateException("Unbalanced starter-data VALUES parentheses");
                }
            } else if (depth == 0 && character != ',' && !Character.isWhitespace(character)) {
                throw new IllegalStateException("Unexpected starter-data VALUES text: " + character);
            }
        }
        if (quote != 0 || depth != 0 || tuples.isEmpty()) {
            throw new IllegalStateException("Incomplete starter-data VALUES expression");
        }
        return tuples;
    }

    private static List<String> splitExpressions(String sql) {
        List<String> parts = new ArrayList<>();
        char quote = 0;
        int depth = 0;
        int start = 0;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;
            if (quote != 0) {
                if (character == '\\' && next != 0) {
                    index++;
                } else if (character == quote && next == quote) {
                    index++;
                } else if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"' || character == '`') {
                quote = character;
            } else if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            } else if (character == ',' && depth == 0) {
                parts.add(sql.substring(start, index).trim());
                start = index + 1;
            }
        }
        parts.add(sql.substring(start).trim());
        return parts;
    }

    private static boolean schemaExists(Connection connection, String schema) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM information_schema.schemata WHERE schema_name=?")) {
            query.setString(1, schema);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    static List<String> schemaStatements(Engine engine, String module) throws IOException {
        String file = module.equals("api") ? "chatchat-api.sql" : "chatchat-mcp-server.sql";
        List<String> statements = splitSql(MigrationSchema.resourceSql(engine.name().toLowerCase(), file));
        for (String sql : statements) {
            if (!CREATE_TABLE.matcher(sql).find() && !CREATE_INDEX.matcher(sql).find()
                    && !ALTER_CONSTRAINT.matcher(sql).find()) {
                throw new IllegalStateException("Unsupported statement in " + file + ": "
                        + sql.substring(0, Math.min(sql.length(), 100)));
            }
        }
        return statements;
    }

    private static boolean indexExists(Connection connection, Engine engine, String schema, String index)
            throws SQLException {
        String sql = engine == Engine.MYSQL
                ? "SELECT 1 FROM information_schema.statistics WHERE table_schema=? AND lower(index_name)=lower(?)"
                : "SELECT 1 FROM pg_indexes WHERE schemaname=? AND indexname=?";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, schema);
            query.setString(2, index);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean constraintExists(Connection connection, String schema, String table, String name)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM information_schema.table_constraints "
                + "WHERE table_schema=? AND table_name=? AND lower(constraint_name)=lower(?)")) {
            query.setString(1, schema);
            query.setString(2, table);
            query.setString(3, name);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String unquote(String name) {
        return identifier(name.replace("`", "").replace("\"", ""));
    }

    /** Split repository SQL scripts while preserving semicolons and comment markers inside quoted strings. */
    static List<String> splitSql(String script) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < script.length(); index++) {
            char character = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : 0;
            if (lineComment) {
                if (character == '\n' || character == '\r') {
                    lineComment = false;
                    current.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (character == '*' && next == '/') {
                    blockComment = false;
                    index++;
                    current.append(' ');
                }
                continue;
            }
            if (quote != 0) {
                current.append(character);
                if (character == '\\' && next != 0) {
                    current.append(next);
                    index++;
                } else if (character == quote && next == quote) {
                    current.append(next);
                    index++;
                } else if (character == quote) {
                    quote = 0;
                }
            } else if (character == '-' && next == '-' && (index + 2 >= script.length()
                    || Character.isWhitespace(script.charAt(index + 2)))) {
                lineComment = true;
                index++;
            } else if (character == '/' && next == '*') {
                blockComment = true;
                index++;
            } else if (character == '\'' || character == '"' || character == '`') {
                quote = character;
                current.append(character);
            } else if (character == ';') {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    result.add(statement);
                }
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (quote != 0 || blockComment) {
            throw new IllegalArgumentException("Unterminated SQL quote or comment in packaged initialization script");
        }
        String trailing = current.toString().trim();
        if (!trailing.isEmpty()) {
            result.add(trailing);
        }
        return result;
    }
}
