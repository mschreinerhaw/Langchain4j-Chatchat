package com.chatchat.mcpserver.metadata;

import com.chatchat.mcpserver.sql.SqlMetadataSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns enterprise-metadata-specific request adaptation.
 *
 * <p>The Agent Runtime transports declared dependency evidence without understanding
 * its schema. This adapter is the capability boundary that understands model field
 * drafts and SQL metadata result shapes.</p>
 */
@Component
@Slf4j
public class EnterpriseMetadataRequestAdapter {

    private static final Pattern QUALIFIED_IDENTIFIER = Pattern.compile(
        "(?i)(?<![a-z0-9_$])(?:[a-z][a-z0-9_$]*\\.)?[a-z][a-z0-9_$]*(?:_[a-z0-9_$]+)+(?![a-z0-9_$])"
    );

    private final SqlMetadataSearchService sqlMetadataSearchService;

    public EnterpriseMetadataRequestAdapter(SqlMetadataSearchService sqlMetadataSearchService) {
        this.sqlMetadataSearchService = sqlMetadataSearchService;
    }

    public Map<String, Object> adapt(Map<String, Object> input) {
        Map<String, Object> request = new LinkedHashMap<>(input == null ? Map.of() : input);
        Extraction extraction = explicitFields(request.get("fields"));
        if (extraction.fields().isEmpty()) {
            extraction = fieldsFromDependencyEvidence(request.get("sourceEvidence"));
        }
        if (extraction.fields().isEmpty()) {
            extraction = fieldsFromInternalTableLookup(request);
        }
        request.remove("sourceEvidence");
        if (extraction.fields().isEmpty()) {
            return request;
        }

        request.put("fields", extraction.fields());
        request.putIfAbsent("purpose", extraction.fromDependencyEvidence()
            ? "EXISTING_TABLE_METADATA_ALIGNMENT"
            : "CREATE_TABLE_FIELD_MAPPING");
        request.putIfAbsent("matchMode", "FIELD_MAPPING");
        request.remove("types");

        Map<String, Object> targetObject = map(request.get("targetObject"));
        targetObject.putIfAbsent("type", "TABLE");
        String tableName = firstText(
            text(targetObject.get("name")),
            text(request.get("tableName")),
            text(request.get("table")),
            extraction.qualifiedTable()
        );
        if (tableName != null) {
            targetObject.putIfAbsent("name", tableName);
        }
        putIfText(targetObject, "assetName", extraction.assetName());
        putIfText(targetObject, "database", extraction.database());
        putIfText(targetObject, "tableName", extraction.tableName());
        request.put("targetObject", Map.copyOf(targetObject));
        if (extraction.sourceMode() != null) {
            request.put("schemaEvidence", mapOf(
                "mode", extraction.sourceMode(),
                "assetName", extraction.assetName(),
                "database", extraction.database(),
                "tableName", extraction.tableName(),
                "fieldCount", extraction.fields().size()
            ));
        }

        request.remove("tableName");
        request.remove("table");
        Set<String> queryTerms = new LinkedHashSet<>();
        for (Map<String, Object> field : extraction.fields()) {
            addText(queryTerms, field.get("fieldName"));
            addText(queryTerms, field.get("fieldCnName"));
            addText(queryTerms, field.get("description"));
        }
        addText(queryTerms, tableName);
        request.put("query", String.join(" ", queryTerms));
        return request;
    }

    private Extraction explicitFields(Object value) {
        List<Map<String, Object>> fields = canonicalFields(value);
        return new Extraction(fields, false, null, null, null, null, null);
    }

    private Extraction fieldsFromDependencyEvidence(Object value) {
        if (!(value instanceof Iterable<?> evidenceItems)) {
            return Extraction.empty();
        }
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Object evidenceItem : evidenceItems) {
            Map<String, Object> envelope = map(evidenceItem);
            Object output = envelope.containsKey("output") ? envelope.get("output") : evidenceItem;
            tables.addAll(tableResults(output));
        }
        return extractionFromTables(tables, "DECLARED_DEPENDENCY_EVIDENCE");
    }

    private Extraction fieldsFromInternalTableLookup(Map<String, Object> request) {
        if (sqlMetadataSearchService == null) {
            return Extraction.empty();
        }
        for (String candidate : tableCandidates(request)) {
            Map<String, Object> lookup = new LinkedHashMap<>();
            copyIfPresent(request, lookup, "requestId");
            copyIfPresent(request, lookup, "defaultDataAsset");
            copyIfPresent(request, lookup, "assetSelectionPolicy");
            copyIfPresent(request, lookup, "mcpExecutionContext");
            copyIfPresent(request, lookup, "mcpContext");
            copyIfPresent(request, lookup, "tenantId");
            copyIfPresent(request, lookup, "userId");
            lookup.put("query", candidate);
            lookup.put("tableName", candidate);
            lookup.put("includeColumns", true);
            lookup.put("detailLimit", 1);
            lookup.put("catalogLimit", 20);
            log.info("enterprise_metadata_search resolving table schema internally table={} requestId={}",
                candidate, text(request.get("requestId")));
            Map<String, Object> result = sqlMetadataSearchService.search(lookup);
            List<Map<String, Object>> exactTables = tableResults(result).stream()
                .filter(table -> sameTable(candidate, table))
                .filter(table -> table.get("columns") instanceof Iterable<?>)
                .toList();
            Extraction extraction = extractionFromTables(
                exactTables, "INTERNAL_SQL_METADATA_LOOKUP");
            if (!extraction.fields().isEmpty()) {
                log.info("enterprise_metadata_search internal table schema resolved table={} fieldCount={} sourceAsset={} database={}",
                    candidate, extraction.fields().size(), extraction.assetName(), extraction.database());
                return extraction;
            }
        }
        return Extraction.empty();
    }

    private Extraction extractionFromTables(List<Map<String, Object>> tables, String sourceMode) {
        List<Map<String, Object>> rawFields = new ArrayList<>();
        String database = null;
        String tableName = null;
        String assetName = null;
        for (Map<String, Object> table : tables) {
            Map<String, Object> location = map(table.get("location"));
            Map<String, Object> asset = map(table.get("asset"));
            database = firstText(database,
                text(location.get("database")), text(location.get("schema")));
            tableName = firstText(tableName,
                text(location.get("tableName")), text(location.get("table")));
            assetName = firstText(assetName,
                text(asset.get("name")), text(asset.get("displayName")));
            Object columns = table.get("columns");
            if (columns instanceof Iterable<?> values) {
                for (Object column : values) {
                    rawFields.add(map(column));
                }
            }
        }
        List<Map<String, Object>> fields = canonicalFields(rawFields);
        String qualifiedTable = tableName == null ? null
            : database == null ? tableName : database + "." + tableName;
        return new Extraction(fields, !fields.isEmpty(), database, tableName,
            assetName, qualifiedTable, sourceMode);
    }

    private List<String> tableCandidates(Map<String, Object> request) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        Map<String, Object> target = map(request.get("targetObject"));
        addTableCandidate(candidates, target.get("tableName"));
        addTableCandidate(candidates, target.get("name"));
        addTableCandidate(candidates, request.get("tableName"));
        addTableCandidate(candidates, request.get("table"));
        String query = text(request.get("query"));
        if (query != null) {
            Matcher matcher = QUALIFIED_IDENTIFIER.matcher(query);
            while (matcher.find() && candidates.size() < 5) {
                addTableCandidate(candidates, matcher.group());
            }
        }
        return List.copyOf(candidates);
    }

    private void addTableCandidate(Set<String> candidates, Object value) {
        String text = text(value);
        if (text != null && QUALIFIED_IDENTIFIER.matcher(text).matches()) {
            candidates.add(text);
        }
    }

    private boolean sameTable(String requested, Map<String, Object> table) {
        String expected = requested;
        int dot = expected.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < expected.length()) {
            expected = expected.substring(dot + 1);
        }
        Map<String, Object> location = map(table.get("location"));
        String actual = firstText(
            text(location.get("tableName")),
            text(location.get("table")),
            text(table.get("tableName")),
            text(table.get("table"))
        );
        return actual != null && normalize(actual).equals(normalize(expected));
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            if (values[index + 1] != null) {
                result.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return Map.copyOf(result);
    }

    private List<Map<String, Object>> tableResults(Object output) {
        Map<String, Object> root = map(output);
        for (String wrapper : List.of("data", "structuredContent")) {
            Map<String, Object> nested = map(root.get(wrapper));
            List<Map<String, Object>> results = listAt(nested, "topTables", "results");
            if (!results.isEmpty()) {
                return results;
            }
            Map<String, Object> nestedStructured = map(nested.get("structuredContent"));
            results = listAt(nestedStructured, "topTables", "results");
            if (!results.isEmpty()) {
                return results;
            }
        }
        return listAt(root, "topTables", "results");
    }

    private List<Map<String, Object>> listAt(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            Object candidate = value.get(key);
            if (!(candidate instanceof Iterable<?> items)) {
                continue;
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : items) {
                Map<String, Object> mapped = map(item);
                if (!mapped.isEmpty()) {
                    result.add(mapped);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> canonicalFields(Object value) {
        if (!(value instanceof Iterable<?> values)) {
            return List.of();
        }
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        for (Object item : values) {
            Map<String, Object> source = map(item);
            String fieldName = firstText(
                text(source.get("fieldName")), text(source.get("columnName")),
                text(source.get("physicalName")), text(source.get("enName")),
                text(source.get("englishName")), text(source.get("name")));
            String fieldCnName = firstText(
                text(source.get("fieldCnName")), text(source.get("cnName")),
                text(source.get("chineseName")), text(source.get("businessName")),
                text(source.get("label")), text(source.get("comment")));
            if (fieldName == null && fieldCnName == null) {
                continue;
            }
            Map<String, Object> field = new LinkedHashMap<>();
            putIfText(field, "fieldName", fieldName);
            putIfText(field, "fieldCnName", fieldCnName);
            putIfText(field, "description", firstText(
                text(source.get("description")), text(source.get("comment")),
                text(source.get("remark"))));
            putIfText(field, "dataType", firstText(
                text(source.get("dataType")), text(source.get("columnType")),
                text(source.get("type"))));
            Boolean nullable = bool(firstValue(source, "nullable", "isNullable"));
            if (nullable != null) {
                field.put("nullable", nullable);
            }
            putIfText(field, "defaultValue", firstText(
                text(source.get("defaultValue")), text(source.get("default"))));
            putIfText(field, "domain", firstText(
                text(source.get("domain")), text(source.get("businessDomain"))));
            String key = normalize(firstText(fieldName, fieldCnName));
            fields.putIfAbsent(key, Map.copyOf(field));
        }
        return List.copyOf(fields.values());
    }

    private Object firstValue(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            if (value.containsKey(key) && value.get(key) != null) {
                return value.get(key);
            }
        }
        return null;
    }

    private Boolean bool(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return null;
        }
        String normalized = text(value);
        if (normalized == null) {
            return null;
        }
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "是", "可空", "nullable" -> true;
            case "false", "0", "no", "n", "否", "非空", "notnull" -> false;
            default -> null;
        };
    }

    private void addText(Set<String> values, Object value) {
        String text = text(value);
        if (text != null) {
            values.add(text);
        }
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.putIfAbsent(key, value);
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private record Extraction(
        List<Map<String, Object>> fields,
        boolean fromDependencyEvidence,
        String database,
        String tableName,
        String assetName,
        String qualifiedTable,
        String sourceMode
    ) {
        private static Extraction empty() {
            return new Extraction(List.of(), false, null, null, null, null, null);
        }
    }
}
