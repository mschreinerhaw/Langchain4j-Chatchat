package com.chatchat.migration;

import static com.chatchat.migration.DataMigrationApplication.columns;
import static com.chatchat.migration.DataMigrationApplication.connect;
import static com.chatchat.migration.DataMigrationApplication.count;
import static com.chatchat.migration.DataMigrationApplication.dependencies;
import static com.chatchat.migration.DataMigrationApplication.identifier;
import static com.chatchat.migration.DataMigrationApplication.parentFirst;
import static com.chatchat.migration.DataMigrationApplication.qualified;
import static com.chatchat.migration.DataMigrationApplication.quoted;
import static com.chatchat.migration.DataMigrationApplication.resetSequences;
import static com.chatchat.migration.DataMigrationApplication.rowExists;
import static com.chatchat.migration.DataMigrationApplication.setting;
import static com.chatchat.migration.DataMigrationApplication.tables;

import com.chatchat.migration.DataMigrationApplication.Column;
import com.chatchat.migration.DataMigrationApplication.Engine;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Portable archive for moving a complete API or MCP relational database between environments. */
final class DataFileTransfer {
    private static final int MAGIC = 0x43434442; // CCDB
    private static final int VERSION = 1;
    private static final int MAX_CELL_BYTES = 128 * 1024 * 1024;
    private static final byte NULL = 0, BINARY = 1, BOOLEAN = 2, LOCAL_TIME = 3, OFFSET_TIME = 4, TEXT = 5;
    private static final Set<String> BINARY_TYPES = Set.of("binary", "varbinary", "tinyblob", "blob",
            "mediumblob", "longblob", "bytea");
    private static final Set<String> DECIMAL_TYPES = Set.of("decimal", "numeric", "real", "double precision",
            "float", "double");
    private static final Set<String> INTEGER_TYPES = Set.of("tinyint", "smallint", "mediumint", "int",
            "integer", "bigint");

    private DataFileTransfer() { }

    static void execute(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.exportFile != null) {
            exportFile(options);
        } else {
            importFile(options);
        }
    }

    private static void exportFile(Options options) throws Exception {
        if (Files.exists(options.exportFile)) {
            throw new IllegalArgumentException("Export file already exists: " + options.exportFile);
        }
        Path destination = options.exportFile.toAbsolutePath();
        Path parent = destination.getParent();
        Path partial = Files.createTempFile(parent, destination.getFileName() + ".", ".partial");
        try {
            try (Connection source = connection(options)) {
                source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                source.setReadOnly(true);
                source.setAutoCommit(false);
                String scope = scope(source, options.engine);
                Set<String> found = tables(source, scope);
                requireModuleSchema(options.module, found);
                List<String> order = parentFirst(found, dependencies(source, options.engine, scope));
                Properties manifest = new Properties();
                manifest.setProperty("format", Integer.toString(VERSION));
                manifest.setProperty("module", options.module);
                manifest.setProperty("engine", options.engine.name().toLowerCase());
                manifest.setProperty("tables", String.join(",", order));
                long total = 0;
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(partial), StandardCharsets.UTF_8)) {
                    for (String table : order) {
                        LinkedHashMap<String, Column> fields = columns(source, options.engine, scope, table);
                        long expected = count(source, options.engine, scope, table);
                        zip.putNextEntry(new ZipEntry(entryName(table)));
                        MessageDigest digest = sha256();
                        DataOutputStream output = new DataOutputStream(new DigestOutputStream(zip, digest));
                        writeHeader(output, table, fields, expected);
                        long exported = exportTable(source, options.engine, scope, table, fields, output, options.batchSize);
                        output.flush();
                        zip.closeEntry();
                        if (exported != expected) {
                            throw new IllegalStateException("Source row count changed during export: " + table);
                        }
                        manifest.setProperty("table." + table + ".rows", Long.toString(exported));
                        manifest.setProperty("table." + table + ".sha256", hex(digest.digest()));
                        total += exported;
                        System.out.printf("%s: %d rows%n", table, exported);
                    }
                    zip.putNextEntry(new ZipEntry("manifest.properties"));
                    manifest.store(zip, "ChatChat relational data archive");
                    zip.closeEntry();
                } finally {
                    source.rollback();
                }
                Files.move(partial, destination);
                System.out.printf("Export completed: %s; %d rows across %d tables.%n", destination, total, order.size());
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static void importFile(Options options) throws Exception {
        try (ZipFile archive = new ZipFile(options.importFile.toFile(), StandardCharsets.UTF_8)) {
            Properties manifest = manifest(archive);
            List<String> archivedTables = archiveTables(manifest, options.module);
            Map<String, Header> headers = validateArchive(archive, manifest, archivedTables);
            try (Connection target = connection(options)) {
                target.setAutoCommit(false);
                String scope = scope(target, options.engine);
                try {
                    Set<String> found = tables(target, scope);
                    requireModuleSchema(options.module, found);
                    if (!found.equals(new HashSet<>(archivedTables))) {
                        throw new IllegalStateException("Target tables differ from archive");
                    }
                    Map<String, LinkedHashMap<String, Column>> targetColumns = new HashMap<>();
                    for (String table : archivedTables) {
                        LinkedHashMap<String, Column> columns = columns(target, options.engine, scope, table);
                        Set<String> archivedNames = new HashSet<>(headers.get(table).fields.keySet());
                        if (!columns.keySet().equals(archivedNames)) {
                            throw new IllegalStateException("Target columns differ from archive in " + table);
                        }
                        targetColumns.put(table, columns);
                    }
                    List<String> order = parentFirst(found, dependencies(target, options.engine, scope));
                    List<String> populated = new ArrayList<>();
                    for (String table : order) {
                        if (rowExists(target, options.engine, scope, table)) {
                            populated.add(table);
                        }
                    }
                    System.out.printf("Archive -> %s:%s; module=%s; tables=%d%n", options.engine,
                            target.getCatalog(), options.module, order.size());
                    if (options.dryRun) {
                        if (!populated.isEmpty()) {
                            System.out.println("Target has data in " + populated.size()
                                    + " tables; a write run requires --replace-target.");
                        }
                        for (String table : order) {
                            System.out.printf("%s: archive=%d%n", table, headers.get(table).rows);
                        }
                        System.out.println("Archive and target schema verified; target unchanged.");
                        return;
                    }
                    if (!populated.isEmpty() && !options.replaceTarget) {
                        throw new IllegalStateException("Target contains data (first table: " + populated.get(0)
                                + "); use --replace-target to overwrite it.");
                    }
                    if (options.replaceTarget) {
                        for (int index = order.size() - 1; index >= 0; index--) {
                            try (Statement delete = target.createStatement()) {
                                delete.executeUpdate("DELETE FROM " + qualified(options.engine, scope, order.get(index)));
                            }
                        }
                    }
                    long total = 0;
                    for (String table : order) {
                        long rows = importTable(archive, manifest, target, options, scope, table,
                                headers.get(table), targetColumns.get(table));
                        total += rows;
                        System.out.printf("%s: %d rows%n", table, rows);
                    }
                    if (options.engine == Engine.POSTGRESQL) {
                        resetSequences(target, scope, order);
                    }
                    target.commit();
                    System.out.printf("Import completed: %d rows across %d tables.%n", total, order.size());
                } catch (Exception exception) {
                    target.rollback();
                    throw exception;
                }
            }
        }
    }

    private static Connection connection(Options options) throws SQLException, IOException {
        return connect(options.engine, options.module, setting("MYSQL_DATABASE", "live_runtime_" + options.module),
                setting("PGDATABASE", "live_runtime_" + options.module));
    }

    private static String scope(Connection connection, Engine engine) throws SQLException {
        return engine == Engine.MYSQL ? connection.getCatalog() : identifier(setting("PGSCHEMA", "public"));
    }

    private static void requireModuleSchema(String module, Set<String> tables) {
        Set<String> anchors = module.equals("api")
                ? Set.of("knowledge_ir_unit", "skill_config", "resource_grant")
                : Set.of("mcp_service_config", "mcp_sql_datasource", "mcp_business_category");
        if (!tables.containsAll(anchors)) {
            throw new IllegalStateException("Missing " + module + " schema anchors: " + anchors);
        }
    }

    private static String entryName(String table) {
        return "tables/" + identifier(table) + ".bin";
    }

    static void writeHeader(DataOutputStream output, String table, LinkedHashMap<String, Column> fields,
            long rows) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        writeText(output, table);
        output.writeInt(fields.size());
        for (Column column : fields.values()) {
            writeText(output, column.name());
            writeText(output, column.type());
        }
        output.writeLong(rows);
    }

    private static Header readHeader(DataInputStream input, String expectedTable) throws IOException {
        if (input.readInt() != MAGIC || input.readInt() != VERSION || !readText(input).equals(expectedTable)) {
            throw new IOException("Unsupported archive table header: " + expectedTable);
        }
        int size = input.readInt();
        if (size < 1 || size > 10000) {
            throw new IOException("Invalid column count in " + expectedTable);
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            String name = identifier(readText(input));
            if (fields.put(name, readText(input)) != null) {
                throw new IOException("Duplicate column in " + expectedTable + ": " + name);
            }
        }
        long rows = input.readLong();
        if (rows < 0) {
            throw new IOException("Invalid row count in " + expectedTable);
        }
        return new Header(fields, rows);
    }

    private static long exportTable(Connection source, Engine engine, String scope, String table,
            LinkedHashMap<String, Column> fields, DataOutputStream output, int batchSize) throws SQLException, IOException {
        String selected = String.join(", ", fields.keySet().stream().map(name -> quoted(engine, name)).toList());
        String sql = "SELECT " + selected + " FROM " + qualified(engine, scope, table);
        long rowsWritten = 0;
        try (PreparedStatement query = source.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY)) {
            query.setFetchSize(engine == Engine.MYSQL ? Integer.MIN_VALUE : batchSize);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    int index = 1;
                    for (Column column : fields.values()) {
                        writeCell(output, rows, index++, column.type());
                    }
                    rowsWritten++;
                }
            }
        }
        return rowsWritten;
    }

    private static void writeCell(DataOutputStream output, ResultSet rows, int index, String type)
            throws SQLException, IOException {
        if (rows.getObject(index) == null) {
            output.writeByte(NULL);
        } else if (BINARY_TYPES.contains(type)) {
            output.writeByte(BINARY);
            writeBytes(output, rows.getBytes(index));
        } else if (type.equals("boolean") || type.equals("bit")) {
            output.writeByte(BOOLEAN);
            output.writeBoolean(rows.getBoolean(index));
        } else if (type.equals("timestamp with time zone")) {
            output.writeByte(OFFSET_TIME);
            writeText(output, rows.getObject(index, OffsetDateTime.class).toString());
        } else if (type.equals("datetime") || type.equals("timestamp without time zone")) {
            output.writeByte(LOCAL_TIME);
            writeText(output, rows.getObject(index, LocalDateTime.class).toString());
        } else {
            output.writeByte(TEXT);
            writeText(output, rows.getString(index));
        }
    }

    private static long importTable(ZipFile archive, Properties manifest, Connection target, Options options,
            String scope, String table, Header header, LinkedHashMap<String, Column> targetFields)
            throws SQLException, IOException {
        List<String> names = new ArrayList<>(header.fields.keySet());
        String fields = String.join(", ", names.stream().map(name -> quoted(options.engine, name)).toList());
        String parameters = String.join(", ", java.util.Collections.nCopies(names.size(), "?"));
        String sql = "INSERT INTO " + qualified(options.engine, scope, table) + " (" + fields + ") VALUES ("
                + parameters + ")";
        try (PreparedStatement insert = target.prepareStatement(sql)) {
            int[] pending = {0};
            readTable(archive, manifest, table, (cells, sourceFields) -> {
                for (int index = 0; index < names.size(); index++) {
                    bindCell(insert, index + 1, cells.get(index), targetFields.get(names.get(index)).type(),
                            options.engine, options.mysqlZone);
                }
                insert.addBatch();
                if (++pending[0] == options.batchSize) {
                    insert.executeBatch();
                    pending[0] = 0;
                }
            });
            if (pending[0] > 0) {
                insert.executeBatch();
            }
        }
        long actual = count(target, options.engine, scope, table);
        if (actual != header.rows) {
            throw new IllegalStateException("Row-count mismatch in " + table + ": archive=" + header.rows
                    + ", target=" + actual);
        }
        return actual;
    }

    static void bindCell(PreparedStatement target, int index, Cell cell, String targetType,
            Engine targetEngine, ZoneId mysqlZone)
            throws SQLException {
        if (cell.tag == NULL) {
            target.setObject(index, null);
        } else if (cell.tag == BINARY) {
            target.setBytes(index, cell.bytes);
        } else if (cell.tag == BOOLEAN) {
            target.setBoolean(index, cell.flag);
        } else if (cell.tag == LOCAL_TIME) {
            LocalDateTime local = LocalDateTime.parse(cell.text);
            target.setObject(index, targetType.equals("timestamp with time zone")
                    ? local.atZone(mysqlZone).toOffsetDateTime() : local);
        } else if (cell.tag == OFFSET_TIME) {
            OffsetDateTime time = OffsetDateTime.parse(cell.text);
            target.setObject(index, targetType.equals("timestamp with time zone") ? time
                    : time.atZoneSameInstant(mysqlZone).toLocalDateTime());
        } else if (targetType.equals("boolean") || targetType.equals("bit")) {
            if (!Set.of("0", "1", "true", "false").contains(cell.text.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Invalid boolean value in archive: " + cell.text);
            }
            target.setBoolean(index, cell.text.equals("1") || cell.text.equalsIgnoreCase("true"));
        } else if (targetEngine == Engine.POSTGRESQL && (targetType.equals("json") || targetType.equals("jsonb"))) {
            target.setObject(index, cell.text, Types.OTHER);
        } else if (INTEGER_TYPES.contains(targetType)) {
            target.setLong(index, Long.parseLong(cell.text));
        } else if (targetType.equals("real") || targetType.equals("double precision")
                || targetType.equals("float") || targetType.equals("double")) {
            target.setDouble(index, Double.parseDouble(cell.text));
        } else if (DECIMAL_TYPES.contains(targetType)) {
            target.setBigDecimal(index, new BigDecimal(cell.text));
        } else if (targetType.equals("uuid")) {
            target.setObject(index, java.util.UUID.fromString(cell.text));
        } else {
            target.setString(index, cell.text);
        }
    }

    private static Properties manifest(ZipFile archive) throws IOException {
        ZipEntry entry = archive.getEntry("manifest.properties");
        if (entry == null) {
            throw new IOException("Archive manifest is missing");
        }
        Properties properties = new Properties();
        try (InputStream input = archive.getInputStream(entry)) {
            properties.load(input);
        }
        if (!Integer.toString(VERSION).equals(properties.getProperty("format"))) {
            throw new IOException("Unsupported archive format");
        }
        return properties;
    }

    private static List<String> archiveTables(Properties manifest, String module) throws IOException {
        if (!module.equals(manifest.getProperty("module"))) {
            throw new IOException("Archive module does not match --module " + module);
        }
        String value = manifest.getProperty("tables", "");
        if (value.isBlank()) {
            throw new IOException("Archive has no tables");
        }
        List<String> found = Arrays.stream(value.split(",", -1)).map(DataMigrationApplication::identifier).toList();
        if (new HashSet<>(found).size() != found.size()) {
            throw new IOException("Archive contains duplicate tables");
        }
        return found;
    }

    static Map<String, Header> validateArchive(Path file, String module) throws IOException, SQLException {
        try (ZipFile archive = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            Properties properties = manifest(archive);
            return validateArchive(archive, properties, archiveTables(properties, module));
        }
    }

    private static Map<String, Header> validateArchive(ZipFile archive, Properties manifest, List<String> tables)
            throws IOException, SQLException {
        Map<String, Header> headers = new HashMap<>();
        for (String table : tables) {
            headers.put(table, readTable(archive, manifest, table, (cells, fields) -> { }));
        }
        return headers;
    }

    private static Header readTable(ZipFile archive, Properties manifest, String table, RowConsumer consumer)
            throws IOException, SQLException {
        ZipEntry entry = archive.getEntry(entryName(table));
        if (entry == null) {
            throw new IOException("Archive table is missing: " + table);
        }
        MessageDigest digest = sha256();
        try (InputStream raw = archive.getInputStream(entry);
             DigestInputStream hashed = new DigestInputStream(raw, digest);
             DataInputStream input = new DataInputStream(hashed)) {
            Header header = readHeader(input, table);
            long expected = Long.parseLong(manifest.getProperty("table." + table + ".rows", "-1"));
            if (header.rows != expected) {
                throw new IOException("Archive row count does not match manifest: " + table);
            }
            for (long row = 0; row < header.rows; row++) {
                List<Cell> cells = new ArrayList<>(header.fields.size());
                for (int column = 0; column < header.fields.size(); column++) {
                    cells.add(readCell(input));
                }
                consumer.accept(cells, header.fields);
            }
            if (input.read() != -1) {
                throw new IOException("Unexpected trailing data in " + table);
            }
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(manifest.getProperty("table." + table + ".sha256", ""))) {
                throw new IOException("Archive checksum mismatch: " + table);
            }
            return header;
        }
    }

    private static Cell readCell(DataInputStream input) throws IOException {
        byte tag = input.readByte();
        return switch (tag) {
            case NULL -> new Cell(tag, null, null, false);
            case BINARY -> new Cell(tag, readBytes(input), null, false);
            case BOOLEAN -> new Cell(tag, null, null, input.readBoolean());
            case LOCAL_TIME, OFFSET_TIME, TEXT -> new Cell(tag, null, readText(input), false);
            default -> throw new IOException("Invalid archive cell type: " + tag);
        };
    }

    static void writeText(DataOutputStream output, String value) throws IOException {
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readText(DataInputStream input) throws IOException {
        return new String(readBytes(input), StandardCharsets.UTF_8);
    }

    static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        if (bytes.length > MAX_CELL_BYTES) {
            throw new IOException("Archive cell exceeds 128 MiB");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_CELL_BYTES) {
            throw new IOException("Invalid archive cell length: " + size);
        }
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return bytes;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    record Header(LinkedHashMap<String, String> fields, long rows) { }
    record Cell(byte tag, byte[] bytes, String text, boolean flag) { }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(List<Cell> cells, LinkedHashMap<String, String> fields) throws SQLException, IOException;
    }

    private record Options(Path exportFile, Path importFile, Engine engine, String module, boolean replaceTarget,
                           boolean dryRun, int batchSize, ZoneId mysqlZone) {
        static Options parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            Set<String> flags = new HashSet<>();
            for (int index = 0; index < args.length; index++) {
                String key = args[index];
                if (key.equals("--dry-run") || key.equals("--replace-target")) {
                    flags.add(key);
                } else if (Set.of("--export-file", "--import-file", "--engine", "--module", "--batch-size",
                        "--mysql-timezone").contains(key) && index + 1 < args.length) {
                    values.put(key, args[++index]);
                } else {
                    throw new IllegalArgumentException("Unknown or incomplete argument: " + key);
                }
            }
            boolean export = values.containsKey("--export-file");
            boolean inbound = values.containsKey("--import-file");
            if (export == inbound) {
                throw new IllegalArgumentException("Specify exactly one of --export-file or --import-file");
            }
            String engineName = values.get("--engine");
            String module = values.get("--module");
            if (engineName == null || module == null || !Set.of("mysql", "postgresql").contains(engineName)
                    || !Set.of("api", "mcp").contains(module)) {
                throw new IllegalArgumentException("Specify --engine mysql|postgresql and --module api|mcp");
            }
            if (export && !flags.isEmpty()) {
                throw new IllegalArgumentException("--dry-run and --replace-target apply only to import");
            }
            int batchSize = Integer.parseInt(values.getOrDefault("--batch-size", "500"));
            if (batchSize < 1) {
                throw new IllegalArgumentException("--batch-size must be positive");
            }
            return new Options(export ? Path.of(values.get("--export-file")) : null,
                    inbound ? Path.of(values.get("--import-file")) : null,
                    Engine.valueOf(engineName.toUpperCase()), module, flags.contains("--replace-target"),
                    flags.contains("--dry-run"), batchSize,
                    ZoneId.of(values.getOrDefault("--mysql-timezone", "Asia/Shanghai")));
        }
    }
}
