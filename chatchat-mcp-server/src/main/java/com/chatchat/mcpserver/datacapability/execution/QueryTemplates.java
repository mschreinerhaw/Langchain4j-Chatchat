package com.chatchat.mcpserver.datacapability.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

/** Substitutions represent complete values, never executable query fragments. */
public final class QueryTemplates {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");
    private QueryTemplates() {}

    public static String sql(String template, Map<String, Object> parameters) {
        return TOKEN.matcher(template).replaceAll(match -> {
            String name = match.group(1);
            if (!parameters.containsKey(name)) throw new IllegalArgumentException("Missing SQL parameter: " + name);
            Object value = parameters.get(name);
            String literal;
            if (value == null) literal = "NULL";
            else if (value instanceof Number number) literal = new BigDecimal(number.toString()).toPlainString();
            else if (value instanceof Boolean bool) literal = bool ? "TRUE" : "FALSE";
            else if (value instanceof String text) {
                if (text.indexOf('\\') >= 0 || text.indexOf('\0') >= 0)
                    throw new IllegalArgumentException("SQL string parameters cannot contain backslashes or NUL");
                literal = "'" + text.replace("'", "''") + "'";
            }
            else throw new IllegalArgumentException("SQL parameter must be scalar: " + name);
            return java.util.regex.Matcher.quoteReplacement(literal);
        });
    }

    public static JsonNode json(JsonNode template, Map<String, Object> parameters, ObjectMapper mapper) {
        if (template.isTextual()) {
            var matcher = TOKEN.matcher(template.textValue());
            if (matcher.matches()) {
                if (!parameters.containsKey(matcher.group(1))) throw new IllegalArgumentException("Missing query parameter: " + matcher.group(1));
                return mapper.valueToTree(parameters.get(matcher.group(1)));
            }
        }
        if (template.isObject()) {
            var result = mapper.createObjectNode();
            template.fields().forEachRemaining(e -> result.set(e.getKey(), json(e.getValue(), parameters, mapper)));
            return result;
        }
        if (template.isArray()) {
            var result = mapper.createArrayNode();
            template.forEach(item -> result.add(json(item, parameters, mapper)));
            return result;
        }
        return template.deepCopy();
    }
}
