package com.chatchat.api.controller.agent;

import java.util.regex.Pattern;

/** Redact presentation only; immutable execution bytes and their hashes remain untouched. */
final class CodingArtifactRedactor {
    private static final String KEYS = "password|passwd|pwd|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|shared[_-]?secret|authorization";
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)(?<![A-Za-z0-9_])[\"']?(?:"+KEYS+")[\"']?\\s*[:=]\\s*");
    private static final Pattern BEARER = Pattern.compile("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern USERINFO = Pattern.compile("(?i)([a-z][a-z0-9+.-]{0,31}://)[^/\\s@]+@");
    static String redact(String value) {
        String safe = BEARER.matcher(value).replaceAll("$1[REDACTED]");
        var matcher = ASSIGNMENT.matcher(safe);
        var output = new StringBuilder(safe.length());
        int cursor = 0;
        while (matcher.find(cursor)) {
            int start = matcher.end();
            output.append(safe, cursor, start);
            if (start == safe.length()) { cursor = start; break; }
            char quote = safe.charAt(start);
            if (quote == '\'' || quote == '"') {
                boolean triple = start + 2 < safe.length() && safe.charAt(start+1)==quote && safe.charAt(start+2)==quote;
                int width = triple ? 3 : 1;
                String delimiter = String.valueOf(quote).repeat(width);
                int end = start + width;
                while (end < safe.length()) {
                    if (safe.charAt(end)=='\\') { end=Math.min(safe.length(),end+2); continue; }
                    if (safe.startsWith(delimiter,end)) break;
                    end++;
                }
                output.append(delimiter).append("[REDACTED]").append(delimiter);
                cursor = end == safe.length() ? end : end + width;
            } else {
                int end = start;
                while (end < safe.length() && !Character.isWhitespace(safe.charAt(end)) && ",;".indexOf(safe.charAt(end))<0) end++;
                output.append("[REDACTED]");
                cursor = Math.max(start+1,end);
            }
            if (cursor >= safe.length()) break;
        }
        output.append(safe,cursor,safe.length());
        return USERINFO.matcher(output).replaceAll("$1[REDACTED]@");
    }
}
