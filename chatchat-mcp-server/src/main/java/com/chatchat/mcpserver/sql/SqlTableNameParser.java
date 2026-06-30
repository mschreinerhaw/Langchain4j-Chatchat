package com.chatchat.mcpserver.sql;

final class SqlTableNameParser {

    private SqlTableNameParser() {
    }

    static QualifiedTable parse(String tableName, String database) {
        String explicitDatabase = clean(database);
        String value = clean(tableName);
        if (value == null) {
            return new QualifiedTable(explicitDatabase, explicitDatabase, null);
        }
        String normalized = value
            .replace('`', ' ')
            .replace('"', ' ')
            .replace('[', ' ')
            .replace(']', ' ')
            .trim();
        String[] rawParts = normalized.split("\\.");
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String rawPart : rawParts) {
            String part = clean(rawPart);
            if (part != null) {
                parts.add(part);
            }
        }
        if (parts.size() >= 3) {
            return new QualifiedTable(
                explicitDatabase == null ? parts.get(parts.size() - 3) : explicitDatabase,
                parts.get(parts.size() - 2),
                parts.get(parts.size() - 1)
            );
        }
        if (parts.size() == 2) {
            return new QualifiedTable(
                explicitDatabase == null ? parts.get(parts.size() - 2) : explicitDatabase,
                explicitDatabase == null ? parts.get(parts.size() - 2) : explicitDatabase,
                parts.get(parts.size() - 1)
            );
        }
        return new QualifiedTable(explicitDatabase, explicitDatabase, clean(normalized));
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isBlank()) {
            return null;
        }
        while ((text.startsWith("`") && text.endsWith("`"))
            || (text.startsWith("\"") && text.endsWith("\""))
            || (text.startsWith("[") && text.endsWith("]"))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text.isBlank() ? null : text;
    }

    record QualifiedTable(String database, String schema, String table) {
    }
}
