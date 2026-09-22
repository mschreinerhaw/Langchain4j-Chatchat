package com.chatchat.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Migration scope is defined by the repository's database/init SQL, packaged in the JAR. */
final class MigrationSchema {
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?im)^\\s*create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?[`\"]?([A-Za-z_][A-Za-z0-9_]*)[`\"]?\\s*\\(");

    private MigrationSchema() { }

    static Set<String> expectedTables(String module) throws IOException {
        if (!Set.of("api", "mcp").contains(module)) {
            throw new IllegalArgumentException("Unknown module: " + module);
        }
        String file = module.equals("api") ? "chatchat-api.sql" : "chatchat-mcp-server.sql";
        Set<String> mysql = read("mysql/" + file);
        Set<String> postgresql = read("postgresql/" + file);
        if (!mysql.equals(postgresql)) {
            throw new IllegalStateException("MySQL/PostgreSQL init SQL define different tables for " + module
                    + "; MySQL-only=" + difference(mysql, postgresql)
                    + ", PostgreSQL-only=" + difference(postgresql, mysql));
        }
        return Collections.unmodifiableSet(mysql);
    }

    static Set<String> selectTables(Set<String> databaseTables, Set<String> expected, String location) {
        Set<String> missing = difference(expected, databaseTables);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(location + " is missing tables defined in database/init: " + missing);
        }
        Set<String> ignored = difference(databaseTables, expected);
        if (!ignored.isEmpty()) {
            System.out.println(location + ": skipping tables outside database/init: " + ignored);
        }
        return expected;
    }

    private static Set<String> read(String resource) throws IOException {
        String path = "/migration-schema/" + resource;
        try (InputStream stream = MigrationSchema.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Schema resource missing from JAR: " + path);
            }
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = CREATE_TABLE.matcher(sql);
            Set<String> tables = new TreeSet<>();
            while (matcher.find()) {
                tables.add(matcher.group(1));
            }
            if (tables.isEmpty()) {
                throw new IOException("No CREATE TABLE statements found in " + path);
            }
            return tables;
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }
}
