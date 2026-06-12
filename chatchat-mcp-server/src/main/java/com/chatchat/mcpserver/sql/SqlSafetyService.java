package com.chatchat.mcpserver.sql;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SqlSafetyService {

    private static final Set<String> ALLOWED_FIRST_TOKENS = Set.of("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN");
    private static final Set<String> FORBIDDEN_TOKENS = Set.of(
        "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE", "ALTER", "CREATE", "REPLACE", "MERGE",
        "GRANT", "REVOKE", "CALL", "EXEC", "LOAD", "COPY", "EXPORT", "IMPORT", "SET", "USE",
        "LOCK", "UNLOCK", "ANALYZE", "REPAIR", "OPTIMIZE", "VACUUM"
    );
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");

    public String validateAndNormalize(String sql, int maxRows) {
        String normalized = normalize(sql);
        rejectComments(normalized);
        rejectMultiStatement(normalized);
        rejectForbiddenTokens(normalized);
        String firstToken = firstToken(normalized);
        if (!ALLOWED_FIRST_TOKENS.contains(firstToken)) {
            throw new IllegalArgumentException("Only SELECT, SHOW, DESCRIBE and EXPLAIN SQL are allowed");
        }
        validateAst(normalized, firstToken);
        if ("SELECT".equals(firstToken) && !hasLimit(normalized)) {
            return normalized + " LIMIT " + maxRows;
        }
        return normalized;
    }

    private void validateAst(String sql, String firstToken) {
        if (!"SELECT".equals(firstToken)) {
            return;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select)) {
                throw new IllegalArgumentException("Only SELECT statements are allowed");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("SQL parse failed: " + ex.getMessage());
        }
    }

    private void rejectComments(String sql) {
        if (sql.contains("--") || sql.contains("/*") || sql.contains("*/") || sql.contains("#")) {
            throw new IllegalArgumentException("SQL comments are forbidden");
        }
    }

    private void rejectMultiStatement(String sql) {
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.contains(";")) {
            throw new IllegalArgumentException("Multiple SQL statements are forbidden");
        }
    }

    private void rejectForbiddenTokens(String sql) {
        List<String> tokens = TOKEN_PATTERN.matcher(sql).results()
            .map(match -> match.group().toUpperCase(Locale.ROOT))
            .toList();
        for (String token : tokens) {
            if (FORBIDDEN_TOKENS.contains(token)) {
                throw new IllegalArgumentException("SQL contains forbidden keyword: " + token);
            }
        }
    }

    private boolean hasLimit(String sql) {
        return TOKEN_PATTERN.matcher(sql).results()
            .map(match -> match.group().toUpperCase(Locale.ROOT))
            .anyMatch("LIMIT"::equals);
    }

    private String firstToken(String sql) {
        return TOKEN_PATTERN.matcher(sql).results()
            .map(match -> match.group().toUpperCase(Locale.ROOT))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("SQL cannot be empty"));
    }

    private String normalize(String sql) {
        String value = sql == null ? "" : sql.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("SQL cannot be empty");
        }
        if (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }
}
