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
    private static final Pattern OPTIONAL_TABLE = Pattern.compile(
            "(?im)^\\h*--\\h*migration-optional\\h*\\R\\h*create\\s+table\\s+"
                    + "[`\"]?([A-Za-z_][A-Za-z0-9_]*)[`\"]?\\s*\\(");

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
        return selectTables(databaseTables, expected, Set.of(), location);
    }

    static Set<String> selectTables(Set<String> databaseTables, Set<String> expected, Set<String> optional,
            String location) {
        Set<String> missing = difference(expected, databaseTables);
        missing.removeAll(optional);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(location + " is missing tables defined in database/init: " + missing);
        }
        Set<String> ignored = difference(databaseTables, expected);
        if (!ignored.isEmpty()) {
            System.out.println(location + ": skipping tables outside database/init: " + ignored);
        }
        Set<String> selected = new TreeSet<>(expected);
        selected.retainAll(databaseTables);
        return selected;
    }

    static Set<String> optionalTables(String module) throws IOException {
        if (!Set.of("api", "mcp").contains(module)) {
            throw new IllegalArgumentException("Unknown module: " + module);
        }
        String file = module.equals("api") ? "chatchat-api.sql" : "chatchat-mcp-server.sql";
        Set<String> mysql = readOptional("mysql/" + file);
        Set<String> postgresql = readOptional("postgresql/" + file);
        if (!mysql.equals(postgresql)) {
            throw new IllegalStateException("MySQL/PostgreSQL optional init tables differ for " + module);
        }
        if (!expectedTables(module).containsAll(mysql)) {
            throw new IllegalStateException("Optional table marker has no matching CREATE TABLE in " + file);
        }
        return Collections.unmodifiableSet(mysql);
    }

    private static Set<String> read(String resource) throws IOException {
        return readMatching(resource, CREATE_TABLE);
    }

    private static Set<String> readOptional(String resource) throws IOException {
        return readMatching(resource, OPTIONAL_TABLE);
    }

    private static Set<String> readMatching(String resource, Pattern pattern) throws IOException {
        String path = "/migration-schema/" + resource;
        try (InputStream stream = MigrationSchema.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Schema resource missing from JAR: " + path);
            }
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = pattern.matcher(sql);
            Set<String> tables = new TreeSet<>();
            while (matcher.find()) {
                tables.add(matcher.group(1));
            }
            if (tables.isEmpty() && pattern == CREATE_TABLE) {
                throw new IOException("No CREATE TABLE statements found in " + path);
            }
            return tables;
        }
    }

    static String resourceSql(String engine, String file) throws IOException {
        String path = "/migration-schema/" + engine + "/" + file;
        try (InputStream stream = MigrationSchema.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Schema resource missing from JAR: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }
}
