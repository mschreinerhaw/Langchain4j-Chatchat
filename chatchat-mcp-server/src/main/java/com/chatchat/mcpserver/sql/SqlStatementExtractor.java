package com.chatchat.mcpserver.sql;

import java.util.ArrayList;
import java.util.List;

final class SqlStatementExtractor {

    private SqlStatementExtractor() {
    }

    static List<String> splitStatements(String script) {
        String value = script == null ? "" : script.trim();
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        QuoteMode quoteMode = QuoteMode.NONE;
        String dollarQuoteTag = null;
        String qQuoteEnd = null;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);

            if (dollarQuoteTag != null) {
                if (startsWith(value, index, dollarQuoteTag)) {
                    current.append(dollarQuoteTag);
                    index += dollarQuoteTag.length() - 1;
                    dollarQuoteTag = null;
                } else {
                    current.append(ch);
                }
                continue;
            }

            if (qQuoteEnd != null) {
                if (startsWith(value, index, qQuoteEnd)) {
                    current.append(qQuoteEnd);
                    index += qQuoteEnd.length() - 1;
                    qQuoteEnd = null;
                } else {
                    current.append(ch);
                }
                continue;
            }

            if (quoteMode == QuoteMode.SINGLE) {
                current.append(ch);
                if (ch == '\\' && index + 1 < value.length()) {
                    current.append(value.charAt(++index));
                } else if (ch == '\'' && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    current.append(value.charAt(++index));
                } else if (ch == '\'') {
                    quoteMode = QuoteMode.NONE;
                }
                continue;
            }

            if (quoteMode == QuoteMode.DOUBLE) {
                current.append(ch);
                if (ch == '"' && index + 1 < value.length() && value.charAt(index + 1) == '"') {
                    current.append(value.charAt(++index));
                } else if (ch == '"') {
                    quoteMode = QuoteMode.NONE;
                }
                continue;
            }

            if (quoteMode == QuoteMode.BACKTICK) {
                current.append(ch);
                if (ch == '`' && index + 1 < value.length() && value.charAt(index + 1) == '`') {
                    current.append(value.charAt(++index));
                } else if (ch == '`') {
                    quoteMode = QuoteMode.NONE;
                }
                continue;
            }

            if (quoteMode == QuoteMode.BRACKET) {
                current.append(ch);
                if (ch == ']' && index + 1 < value.length() && value.charAt(index + 1) == ']') {
                    current.append(value.charAt(++index));
                } else if (ch == ']') {
                    quoteMode = QuoteMode.NONE;
                }
                continue;
            }

            if (startsWith(value, index, "--")) {
                index = skipLineComment(value, index + 2, current);
                continue;
            }
            if (ch == '#') {
                index = skipLineComment(value, index + 1, current);
                continue;
            }
            if (startsWith(value, index, "/*")) {
                index = skipBlockComment(value, index + 2, current);
                continue;
            }

            if (ch == '\'') {
                quoteMode = QuoteMode.SINGLE;
                current.append(ch);
                continue;
            }
            if (ch == '"') {
                quoteMode = QuoteMode.DOUBLE;
                current.append(ch);
                continue;
            }
            if (ch == '`') {
                quoteMode = QuoteMode.BACKTICK;
                current.append(ch);
                continue;
            }
            if (ch == '[') {
                quoteMode = QuoteMode.BRACKET;
                current.append(ch);
                continue;
            }

            String qQuote = qQuoteEnd(value, index);
            if (qQuote != null) {
                qQuoteEnd = qQuote;
                current.append(value, index, index + 3);
                index += 2;
                continue;
            }

            String dollarTag = dollarQuoteTag(value, index);
            if (dollarTag != null) {
                dollarQuoteTag = dollarTag;
                current.append(dollarTag);
                index += dollarTag.length() - 1;
                continue;
            }

            if (ch == ';') {
                addStatement(statements, current);
            } else {
                current.append(ch);
            }
        }
        addStatement(statements, current);
        return statements;
    }

    private static int skipLineComment(String value, int index, StringBuilder current) {
        appendSpaceIfNeeded(current);
        for (int cursor = index; cursor < value.length(); cursor++) {
            char ch = value.charAt(cursor);
            if (ch == '\n' || ch == '\r') {
                current.append(ch);
                return cursor;
            }
        }
        return value.length();
    }

    private static int skipBlockComment(String value, int index, StringBuilder current) {
        appendSpaceIfNeeded(current);
        for (int cursor = index; cursor + 1 < value.length(); cursor++) {
            if (value.charAt(cursor) == '*' && value.charAt(cursor + 1) == '/') {
                appendSpaceIfNeeded(current);
                return cursor + 1;
            }
        }
        return value.length();
    }

    private static void appendSpaceIfNeeded(StringBuilder current) {
        if (!current.isEmpty() && !Character.isWhitespace(current.charAt(current.length() - 1))) {
            current.append(' ');
        }
    }

    private static boolean startsWith(String value, int index, String expected) {
        return expected != null && index >= 0 && index + expected.length() <= value.length()
            && value.regionMatches(index, expected, 0, expected.length());
    }

    private static String dollarQuoteTag(String value, int index) {
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

    private static String qQuoteEnd(String value, int index) {
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

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        current.setLength(0);
        if (!statement.isBlank()) {
            statements.add(statement);
        }
    }

    private enum QuoteMode {
        NONE,
        SINGLE,
        DOUBLE,
        BACKTICK,
        BRACKET
    }
}
