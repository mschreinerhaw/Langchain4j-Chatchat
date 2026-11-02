package com.chatchat.mcpserver.database.execution;

import java.util.regex.Pattern;

/** Converts template value placeholders to JDBC names without interpolating parameter values. */
public final class DatabaseQueryParameterSyntax {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");
    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$");

    private DatabaseQueryParameterSyntax() { }

    public static String toNamedParameters(String source) {
        if (source == null || !source.contains("{{")) return source;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < source.length();) {
            int end = i;
            char c = source.charAt(i);
            if (c == '#' || source.startsWith("--", i)) {
                end = source.indexOf('\n', i);
                if (end < 0) end = source.length();
            } else if (source.startsWith("/*", i)) {
                end = i + 2;
                int depth = 1;
                while (end < source.length() && depth > 0) {
                    if (source.startsWith("/*", end)) { depth++; end += 2; }
                    else if (source.startsWith("*/", end)) { depth--; end += 2; }
                    else end++;
                }
            } else if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                end = i + 1;
                while (end < source.length()) {
                    if (source.charAt(end) == '\\') { end = Math.min(source.length(), end + 2); continue; }
                    if (source.charAt(end++) == close) {
                        if (end < source.length() && source.charAt(end) == close) { end++; continue; }
                        break;
                    }
                }
                if ((c == '\'' || c == '"') && end > i + 1 && source.charAt(end - 1) == close) {
                    var quoted = TOKEN.matcher(source.substring(i + 1, end - 1));
                    if (quoted.matches()) {
                        result.append(':').append(quoted.group(1));
                        i = end;
                        continue;
                    }
                }
            } else if (c == '$') {
                var dollar = DOLLAR_QUOTE.matcher(source).region(i, source.length());
                if (dollar.lookingAt()) {
                    int close = source.indexOf(dollar.group(), dollar.end());
                    end = close < 0 ? source.length() : close + dollar.group().length();
                }
            }
            if (end > i) {
                result.append(source, i, end);
                i = end;
                continue;
            }
            var token = TOKEN.matcher(source).region(i, source.length());
            if (token.lookingAt()) {
                result.append(':').append(token.group(1));
                i = token.end();
            } else result.append(source.charAt(i++));
        }
        return result.toString();
    }
}
