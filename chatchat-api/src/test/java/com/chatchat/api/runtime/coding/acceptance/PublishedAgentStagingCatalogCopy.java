package com.chatchat.api.runtime.coding.acceptance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Read-only platform configuration snapshot for an isolated published-Agent acceptance instance. */
public final class PublishedAgentStagingCatalogCopy {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+):([^}]*)}");

    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("Source YAML, staging URL, table list, Agent ID, staging root and manifest required");
        Path root = Path.of(args[4]).toRealPath();
        String targetUrl = args[1];
        if (!targetUrl.startsWith("jdbc:h2:file:")) throw new IllegalArgumentException("Target must be isolated H2");
        Path target = Path.of(targetUrl.substring("jdbc:h2:file:".length()).split(";", 2)[0]).toAbsolutePath().normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Target is outside staging root");
        Map<String, String> settings = settings(Path.of(args[0]));
        Set<String> allowed = new TreeSet<>(Files.readAllLines(Path.of(args[2])));
        Map<String, Long> copied = new TreeMap<>();
        try (Connection source = DriverManager.getConnection(settings.get("url"), settings.get("username"), settings.getOrDefault("password", ""));
             Connection stage = DriverManager.getConnection(targetUrl, "sa", "")) {
            source.setReadOnly(true);
            source.setAutoCommit(false);
            stage.createStatement().execute("SET REFERENTIAL_INTEGRITY FALSE");
            stage.setAutoCommit(false);
            Set<String> sourceTables = tables(source), stageTables = tables(stage);
            for (String table : sourceTables) {
                if (!(allowed.contains(table) || table.startsWith("platform_") || table.startsWith("runtime_semantic_")
                    || table.startsWith("runtime_summary_contract") || table.contains("license"))) continue;
                if (!stageTables.contains(table) || table.contains("audit") || table.contains("session") || table.endsWith("_log")) continue;
                List<String> columns = new ArrayList<>();
                Map<String, Integer> types = new HashMap<>();
                try (ResultSet fields = stage.getMetaData().getColumns(null, "public", table, "%")) {
                    while (fields.next()) types.put(fields.getString("COLUMN_NAME"), fields.getInt("DATA_TYPE"));
                }
                try (PreparedStatement read = source.prepareStatement("SELECT * FROM " + quote(table))) {
                    read.setFetchSize(256);
                    try (ResultSet rows = read.executeQuery()) {
                        ResultSetMetaData metadata = rows.getMetaData();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            String column = metadata.getColumnName(i);
                            if (types.containsKey(column)) columns.add(column);
                        }
                        if (columns.isEmpty()) throw new IllegalStateException("No compatible configuration columns: " + table);
                        stage.createStatement().executeUpdate("DELETE FROM " + quote(table));
                        String names = String.join(",", columns.stream().map(PublishedAgentStagingCatalogCopy::quote).toList());
                        String parameters = String.join(",", Collections.nCopies(columns.size(), "?"));
                        long count = 0;
                        try (PreparedStatement write = stage.prepareStatement("INSERT INTO " + quote(table) + " (" + names + ") VALUES (" + parameters + ")")) {
                            while (rows.next()) {
                                if (++count > 100_000) throw new IllegalStateException("Configuration snapshot ceiling exceeded: " + table);
                                for (int i = 0; i < columns.size(); i++) {
                                    String column = columns.get(i);
                                    Object value = rows.getObject(column);
                                    if (value == null) write.setNull(i + 1, types.get(column));
                                    else if (value instanceof Clob || types.get(column) == Types.CLOB || types.get(column) == Types.VARCHAR
                                        || types.get(column) == Types.LONGVARCHAR || types.get(column) == Types.CHAR) write.setString(i + 1, rows.getString(column));
                                    else if (types.get(column) == Types.BOOLEAN || types.get(column) == Types.BIT) write.setBoolean(i + 1, rows.getBoolean(column));
                                    else write.setObject(i + 1, value);
                                }
                                write.executeUpdate();
                            }
                        }
                        copied.put(table, count);
                    }
                }
            }
            boolean configurationOnly = "--configuration-only".equals(args[3]);
            String original = configurationOnly ? "" : agentHash(source, args[3]);
            String snapshot = configurationOnly ? "" : agentHash(stage, args[3]);
            if (!original.equals(snapshot)) throw new IllegalStateException("Published Agent snapshot differs from source");
            stage.commit();
            stage.createStatement().execute("SET REFERENTIAL_INTEGRITY TRUE");
            String counts = String.join(",", copied.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + e.getValue()).toList());
            Files.writeString(Path.of(args[5]), "{\"sourceReadOnly\":true,\"runtimeHistoryCopied\":false,\"agentId\":\"" + args[3]
                + "\",\"agentContentSha256\":\"" + original + "\",\"agentVerificationApplicable\":" + !configurationOnly
                + ",\"sourceAndStageAgentMatch\":" + !configurationOnly + ",\"tables\":{" + counts + "}}");
            System.out.println("Configuration snapshot: " + copied.size() + " tables; "
                + (configurationOnly ? "Agent verification not applicable; " : "Agent fingerprints match; ") + "source read-only");
        }
    }

    private static Set<String> tables(Connection connection) throws SQLException {
        Set<String> result = new TreeSet<>();
        try (ResultSet rows = connection.getMetaData().getTables(null, connection.getSchema(), "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (rows.next()) result.add(rows.getString("TABLE_NAME"));
        }
        return result;
    }

    private static String agentHash(Connection connection, String agent) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM skill_config WHERE id=?")) {
            query.setString(1, agent);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || !"published".equalsIgnoreCase(row.getString("market_status"))) throw new IllegalStateException("Agent is not published");
                Map<String, String> fields = new TreeMap<>();
                for (int i = 1; i <= row.getMetaData().getColumnCount(); i++) {
                    String key = row.getMetaData().getColumnName(i);
                    if (key.equals("created_at") || key.equals("updated_at")) continue;
                    Object value = row.getObject(i);
                    fields.put(key, value instanceof Boolean flag ? flag.toString() : row.getString(i));
                }
                for (var field : fields.entrySet()) {
                    String value = field.getKey() + "\0" + Objects.toString(field.getValue(), "<null>") + "\0";
                    digest.update(value.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String quote(String name) {
        if (!name.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid SQL identifier");
        return "\"" + name + "\"";
    }

    private static Map<String, String> settings(Path yaml) throws Exception {
        Map<String, String> result = new HashMap<>();
        Properties properties = new Properties();
        Path environment = yaml.getParent().resolve("env.properties");
        if (Files.exists(environment)) try (var reader = Files.newBufferedReader(environment)) { properties.load(reader); }
        for (String line : Files.readAllLines(yaml)) {
            String value = line.trim();
            for (String key : List.of("url", "username", "password")) {
                if (!value.startsWith(key + ":")) continue;
                String raw = value.substring(key.length() + 1).trim();
                if (raw.startsWith("\"") && raw.endsWith("\"")) raw = raw.substring(1, raw.length() - 1);
                var matcher = PLACEHOLDER.matcher(raw);
                StringBuffer resolved = new StringBuffer();
                while (matcher.find()) matcher.appendReplacement(resolved, java.util.regex.Matcher.quoteReplacement(
                    System.getenv().getOrDefault(matcher.group(1), properties.getProperty(matcher.group(1), matcher.group(2)))));
                matcher.appendTail(resolved);
                result.put(key, resolved.toString());
            }
        }
        if (!result.keySet().containsAll(Set.of("url", "username", "password"))) throw new IllegalArgumentException("Incomplete source datasource configuration");
        return result;
    }
}
