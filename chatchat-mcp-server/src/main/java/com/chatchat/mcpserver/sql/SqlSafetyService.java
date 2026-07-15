package com.chatchat.mcpserver.sql;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SqlSafetyService {

    private static final Set<String> ALLOWED_FIRST_TOKENS = Set.of("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN");
    private static final Set<String> EXPLAIN_FORBIDDEN_TOKENS = Set.of(
        "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE", "ALTER", "CREATE", "REPLACE", "MERGE",
        "GRANT", "REVOKE", "CALL", "EXEC", "LOAD", "COPY", "EXPORT", "IMPORT", "SET", "USE",
        "LOCK", "UNLOCK", "ANALYZE", "REPAIR", "OPTIMIZE", "VACUUM"
    );
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");

    public String validateAndNormalize(String sql, int maxRows) {
        String normalized = normalize(sql);
        rejectComments(normalized);
        rejectMultiStatement(normalized);
        String code = codeForKeywordScan(normalized);
        String firstToken = firstToken(code);
        if (!ALLOWED_FIRST_TOKENS.contains(firstToken)) {
            throw new IllegalArgumentException("SQL contains forbidden keyword: " + firstToken);
        }
        rejectUnsafeReadOnlyStructure(code, firstToken);
        return normalized;
    }

    public String validateAndNormalizeScriptStatement(String sql, int maxRows) {
        String normalized = normalize(sql);
        String code = codeForKeywordScan(normalized);
        String firstToken = firstToken(code);
        if (!ALLOWED_FIRST_TOKENS.contains(firstToken)) {
            throw new IllegalArgumentException("SQL contains forbidden keyword: " + firstToken);
        }
        rejectUnsafeReadOnlyStructure(code, firstToken);
        return normalized;
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

    private void rejectUnsafeReadOnlyStructure(String sql, String firstToken) {
        List<String> tokens = TOKEN_PATTERN.matcher(sql).results()
            .map(match -> match.group().toUpperCase(Locale.ROOT))
            .toList();
        if ("EXPLAIN".equals(firstToken)) {
            for (int index = 1; index < tokens.size(); index++) {
                String token = tokens.get(index);
                if (EXPLAIN_FORBIDDEN_TOKENS.contains(token)) {
                    throw new IllegalArgumentException("EXPLAIN contains unsafe executable keyword: " + token);
                }
            }
            return;
        }
        if (!"SELECT".equals(firstToken)) {
            return;
        }
        if (tokens.contains("INTO")) {
            throw new IllegalArgumentException("SELECT INTO is forbidden for read-only execution");
        }
        if (containsSequence(tokens, "FOR", "UPDATE")
            || containsSequence(tokens, "FOR", "NO", "KEY", "UPDATE")
            || containsSequence(tokens, "FOR", "SHARE")
            || containsSequence(tokens, "FOR", "KEY", "SHARE")
            || containsSequence(tokens, "LOCK", "IN", "SHARE", "MODE")) {
            throw new IllegalArgumentException("SELECT locking clauses are forbidden for read-only execution");
        }
    }

    private boolean containsSequence(List<String> tokens, String... sequence) {
        if (tokens == null || sequence == null || sequence.length == 0 || tokens.size() < sequence.length) {
            return false;
        }
        for (int start = 0; start <= tokens.size() - sequence.length; start++) {
            boolean matched = true;
            for (int offset = 0; offset < sequence.length; offset++) {
                if (!sequence[offset].equals(tokens.get(start + offset))) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private String codeForKeywordScan(String sql) {
        String value = sql == null ? "" : sql;
        StringBuilder code = new StringBuilder(value.length());
        QuoteMode quoteMode = QuoteMode.NONE;
        String dollarQuoteTag = null;
        String qQuoteEnd = null;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (dollarQuoteTag != null) {
                if (startsWith(value, index, dollarQuoteTag)) {
                    appendSpaces(code, dollarQuoteTag.length());
                    index += dollarQuoteTag.length() - 1;
                    dollarQuoteTag = null;
                } else {
                    code.append(' ');
                }
                continue;
            }
            if (qQuoteEnd != null) {
                if (startsWith(value, index, qQuoteEnd)) {
                    appendSpaces(code, qQuoteEnd.length());
                    index += qQuoteEnd.length() - 1;
                    qQuoteEnd = null;
                } else {
                    code.append(' ');
                }
                continue;
            }
            if (quoteMode == QuoteMode.SINGLE) {
                code.append(' ');
                if (ch == '\\' && index + 1 < value.length()) {
                    code.append(' ');
                    index++;
                } else if (ch == '\'' && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    code.append(' ');
                    index++;
                } else if (ch == '\'') {
                    quoteMode = QuoteMode.NONE;
                }
                continue;
            }
            if (quoteMode == QuoteMode.DOUBLE) {
                code.append(' ');
                if (ch == '"' && index + 1 < value.length() && value.charAt(index + 1) == '"') {
                    code.append(' ');
                    index++;
                } else if (ch == '"') {
                    quoteMode = QuoteMode.NONE;
                }
                continue;
            }
            if (quoteMode == QuoteMode.BACKTICK) {
                code.append(' ');
                if (ch == '`' && index + 1 < value.length() && value.charAt(index + 1) == '`') {
                    code.append(' ');
                    index++;
                } else if (ch == '`') {
                    quoteMode = QuoteMode.NONE;
                }
                continue;
            }
            if (quoteMode == QuoteMode.BRACKET) {
                code.append(' ');
                if (ch == ']' && index + 1 < value.length() && value.charAt(index + 1) == ']') {
                    code.append(' ');
                    index++;
                } else if (ch == ']') {
                    quoteMode = QuoteMode.NONE;
                }
                continue;
            }

            if (ch == '\'') {
                quoteMode = QuoteMode.SINGLE;
                code.append(' ');
                continue;
            }
            if (ch == '"') {
                quoteMode = QuoteMode.DOUBLE;
                code.append(' ');
                continue;
            }
            if (ch == '`') {
                quoteMode = QuoteMode.BACKTICK;
                code.append(' ');
                continue;
            }
            if (ch == '[') {
                quoteMode = QuoteMode.BRACKET;
                code.append(' ');
                continue;
            }
            String qQuote = qQuoteEnd(value, index);
            if (qQuote != null) {
                qQuoteEnd = qQuote;
                appendSpaces(code, 3);
                index += 2;
                continue;
            }
            String dollarTag = dollarQuoteTag(value, index);
            if (dollarTag != null) {
                dollarQuoteTag = dollarTag;
                appendSpaces(code, dollarTag.length());
                index += dollarTag.length() - 1;
                continue;
            }
            code.append(ch);
        }
        return code.toString();
    }

    private void appendSpaces(StringBuilder builder, int count) {
        for (int index = 0; index < count; index++) {
            builder.append(' ');
        }
    }

    private boolean startsWith(String value, int index, String expected) {
        return expected != null && index >= 0 && index + expected.length() <= value.length()
            && value.regionMatches(index, expected, 0, expected.length());
    }

    private String dollarQuoteTag(String value, int index) {
        if (index >= value.length() || value.charAt(index) != '$') {
            return null;
        }
        int cursor = index + 1;
        while (cursor < value.length()) {
            char ch = value.charAt(cursor);
            if (ch == '$') {
                return value.substring(index, cursor + 1);
            }
            if (!(Character.isLetterOrDigit(ch) || ch == '_')) {
                return null;
            }
            cursor++;
        }
        return null;
    }

    private String qQuoteEnd(String value, int index) {
        if (index + 2 >= value.length()
            || (value.charAt(index) != 'q' && value.charAt(index) != 'Q')
            || value.charAt(index + 1) != '\'') {
            return null;
        }
        char delimiter = value.charAt(index + 2);
        char close = switch (delimiter) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> delimiter;
        };
        return String.valueOf(close) + "'";
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

    private enum QuoteMode {
        NONE,
        SINGLE,
        DOUBLE,
        BACKTICK,
        BRACKET
    }
}
