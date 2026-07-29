package com.chatchat.agents.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a model-assisted search projection for enterprise metadata matching.
 *
 * <p>The projection is temporary tool input. Dependency evidence remains unchanged in
 * the execution graph and evidence store; model failure always falls back to the
 * deterministic queryTerms/sourceEvidence path.</p>
 */
@Slf4j
class EnterpriseMetadataSearchBridge {

    static final String PROFILE_VERSION = "enterprise_metadata_search_profile.v1";
    private static final int MAX_EVIDENCE_FIELDS = 80;
    private static final int MAX_PROFILE_FIELDS = 60;
    private static final int MAX_TERMS = 120;

    private final ObjectMapper objectMapper;

    EnterpriseMetadataSearchBridge(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    Map<String, Object> enrich(ChatModel chatModel,
                               String toolName,
                               Map<String, Object> arguments) {
        Map<String, Object> original = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if (chatModel == null || !isEnterpriseMetadataSearch(toolName)
            || (!hasSearchIntent(original) && !hasSqlMetadataEvidence(original))) {
            return original;
        }
        try {
            EvidenceProjection evidence = evidenceProjection(original.get("sourceEvidence"));
            String prompt = buildPrompt(original, evidence);
            Map<String, Object> profile = parseProfile(chatModel.chat(prompt));
            if (text(profile.get("searchIntent")) == null
                && stringValues(profile.get("queryTerms")).isEmpty()
                && !(profile.get("fields") instanceof Iterable<?>)) {
                return original;
            }
            List<Map<String, Object>> fields = normalizedFields(profile.get("fields"));
            if (evidence.fieldCount() > 0) {
                fields = constrainToPhysicalEvidence(fields, evidence);
            }
            List<String> terms = mergedTerms(original, profile, fields);
            if (fields.isEmpty() && terms.isEmpty()) {
                return original;
            }
            if (!fields.isEmpty()) {
                original.put("fields", fields);
                original.putIfAbsent("purpose", evidence.fieldCount() > 0
                    ? "EXISTING_TABLE_METADATA_ALIGNMENT"
                    : "CREATE_TABLE_FIELD_MAPPING");
            }
            if (!terms.isEmpty()) {
                original.put("queryTerms", terms);
                original.put("query", String.join(" ", terms));
            }
            original.put("schemaEvidence", mapOf(
                "mode", evidence.fieldCount() > 0
                    ? "MODEL_ASSISTED_SQL_METADATA_PROJECTION"
                    : "MODEL_ASSISTED_CREATE_TABLE_PROJECTION",
                "profileVersion", PROFILE_VERSION,
                "executionStatus", "NOT_EXECUTED",
                "modelOutputIsSearchProjection", true,
                "physicalEvidencePreserved", true,
                "sourceTableCount", evidence.tableCount(),
                "sourceFieldCount", evidence.fieldCount(),
                "projectedFieldCount", fields.size()
            ));
            original.put("modelSearchProfile", mapOf(
                "schemaVersion", PROFILE_VERSION,
                "mode", evidence.fieldCount() > 0 ? "SQL_METADATA_ASSISTED" : "CREATE_TABLE_ASSISTED",
                "searchIntent", text(profile.get("searchIntent")),
                "fieldCount", fields.size(),
                "termCount", terms.size()
            ));
            log.info("Enterprise metadata model bridge enriched tool input tool={} sourceTables={} "
                    + "sourceFields={} projectedFields={} queryTerms={}",
                toolName, evidence.tableCount(), evidence.fieldCount(), fields.size(), terms.size());
            return original;
        } catch (Exception ex) {
            log.warn("Enterprise metadata model bridge fell back to deterministic input tool={} reason={}",
                toolName, ex.getMessage());
            return original;
        }
    }

    private String buildPrompt(Map<String, Object> arguments, EvidenceProjection evidence) throws Exception {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("query", arguments.get("query"));
        context.put("queryTerms", stringValues(arguments.get("queryTerms")));
        context.put("targetObject", map(arguments.get("targetObject")));
        context.put("sqlMetadata", evidence.tables());
        return """
            You build a search profile for enterprise metadata verification.
            Return JSON only, without markdown:
            {
              "searchIntent":"short description",
              "queryTerms":["precise Chinese/English business and technical search terms"],
              "fields":[{
                "fieldName":"technical name when known or a conservative draft name",
                "fieldCnName":"Chinese business name",
                "description":"business meaning to verify",
                "dataType":"copy from SQL metadata when present; otherwise omit",
                "domain":"optional business domain"
              }]
            }

            Rules:
            1. If sqlMetadata contains columns, select and normalize the columns relevant to the query.
               Never invent a physical column or physical data type. Copy technical names and types exactly.
            2. If sqlMetadata is empty, this is a possible CREATE TABLE request. Infer a practical draft
               field search profile from the query and queryTerms. These are candidates for metadata
               retrieval, not confirmed enterprise facts.
            3. Include aliases and Chinese/English synonyms in queryTerms, but do not include explanations.
            4. Keep no more than %d fields and %d terms. Do not make reuse/compliance decisions.

            Input:
            %s
            """.formatted(MAX_PROFILE_FIELDS, MAX_TERMS, objectMapper.writeValueAsString(context));
    }

    private Map<String, Object> parseProfile(String raw) throws Exception {
        String json = text(raw);
        if (json == null) {
            return Map.of();
        }
        if (json.startsWith("```")) {
            int firstLine = json.indexOf('\n');
            int closing = json.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                json = json.substring(firstLine + 1, closing).trim();
            }
        }
        int objectStart = json.indexOf('{');
        int objectEnd = json.lastIndexOf('}');
        if (objectStart < 0 || objectEnd <= objectStart) {
            throw new IllegalArgumentException("model response did not contain a JSON object");
        }
        return objectMapper.readValue(
            json.substring(objectStart, objectEnd + 1),
            new TypeReference<Map<String, Object>>() { });
    }

    private List<Map<String, Object>> normalizedFields(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Object item : iterable) {
            Map<String, Object> source = map(item);
            String fieldName = firstText(source, "fieldName", "name", "columnName");
            String fieldCnName = firstText(source, "fieldCnName", "chineseName", "businessName");
            if (fieldName == null && fieldCnName == null) {
                continue;
            }
            Map<String, Object> field = new LinkedHashMap<>();
            putText(field, "fieldName", fieldName);
            putText(field, "fieldCnName", fieldCnName);
            putText(field, "description", firstText(source, "description", "comment"));
            putText(field, "dataType", firstText(source, "dataType", "columnType", "type"));
            putText(field, "domain", firstText(source, "domain", "businessDomain"));
            String key = normalize(fieldName == null ? fieldCnName : fieldName);
            unique.putIfAbsent(key, Map.copyOf(field));
            if (unique.size() >= MAX_PROFILE_FIELDS) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    private List<Map<String, Object>> constrainToPhysicalEvidence(
        List<Map<String, Object>> proposed,
        EvidenceProjection evidence
    ) {
        Map<String, Map<String, Object>> physical = new LinkedHashMap<>();
        for (Map<String, Object> table : evidence.tables()) {
            Object columns = table.get("columns");
            if (!(columns instanceof Iterable<?> iterable)) {
                continue;
            }
            for (Object item : iterable) {
                Map<String, Object> column = map(item);
                String fieldName = text(column.get("fieldName"));
                if (fieldName != null) {
                    physical.putIfAbsent(normalize(fieldName), column);
                }
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> field : proposed) {
            String fieldName = text(field.get("fieldName"));
            Map<String, Object> observed = fieldName == null ? null : physical.get(normalize(fieldName));
            if (observed == null) {
                continue;
            }
            Map<String, Object> verified = new LinkedHashMap<>(field);
            verified.put("fieldName", observed.get("fieldName"));
            if (observed.get("dataType") != null) {
                verified.put("dataType", observed.get("dataType"));
            } else {
                verified.remove("dataType");
            }
            if (observed.get("nullable") != null) {
                verified.put("nullable", observed.get("nullable"));
            }
            result.add(Map.copyOf(verified));
        }
        return List.copyOf(result);
    }

    private List<String> mergedTerms(Map<String, Object> original,
                                     Map<String, Object> profile,
                                     List<Map<String, Object>> fields) {
        Set<String> terms = new LinkedHashSet<>();
        addTexts(terms, original.get("queryTerms"));
        addTexts(terms, profile.get("queryTerms"));
        addText(terms, original.get("query"));
        for (Map<String, Object> field : fields) {
            addText(terms, field.get("fieldName"));
            addText(terms, field.get("fieldCnName"));
            addText(terms, field.get("description"));
        }
        return terms.stream().limit(MAX_TERMS).toList();
    }

    private EvidenceProjection evidenceProjection(Object sourceEvidence) {
        List<Map<String, Object>> tables = new ArrayList<>();
        int[] fieldCount = {0};
        collectTables(sourceEvidence, tables, fieldCount, 0);
        return new EvidenceProjection(List.copyOf(tables), tables.size(), fieldCount[0]);
    }

    private void collectTables(Object value,
                               List<Map<String, Object>> tables,
                               int[] fieldCount,
                               int depth) {
        if (value == null || depth > 10 || fieldCount[0] >= MAX_EVIDENCE_FIELDS) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectTables(item, tables, fieldCount, depth + 1);
                if (fieldCount[0] >= MAX_EVIDENCE_FIELDS) {
                    return;
                }
            }
            return;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, Object> current = map(raw);
        if (current.get("columns") instanceof Iterable<?> columns) {
            List<Map<String, Object>> projectedColumns = new ArrayList<>();
            for (Object column : columns) {
                if (fieldCount[0] >= MAX_EVIDENCE_FIELDS) {
                    break;
                }
                Map<String, Object> projected = projectColumn(map(column));
                if (!projected.isEmpty()) {
                    projectedColumns.add(projected);
                    fieldCount[0]++;
                }
            }
            if (!projectedColumns.isEmpty()) {
                tables.add(mapOf(
                    "location", map(current.get("location")),
                    "asset", map(current.get("asset")),
                    "columns", List.copyOf(projectedColumns)
                ));
            }
        }
        for (Object nested : current.values()) {
            collectTables(nested, tables, fieldCount, depth + 1);
            if (fieldCount[0] >= MAX_EVIDENCE_FIELDS) {
                return;
            }
        }
    }

    private Map<String, Object> projectColumn(Map<String, Object> column) {
        Map<String, Object> projected = new LinkedHashMap<>();
        copyFirstText(projected, "fieldName", column, "fieldName", "columnName", "name", "physicalName");
        copyFirstText(projected, "fieldCnName", column, "fieldCnName", "comment", "label", "businessName");
        copyFirstText(projected, "description", column, "description", "comment", "remark");
        copyFirstText(projected, "dataType", column, "dataType", "columnType", "type");
        if (column.get("nullable") != null) {
            projected.put("nullable", column.get("nullable"));
        }
        return Map.copyOf(projected);
    }

    private boolean hasSearchIntent(Map<String, Object> value) {
        return text(value.get("query")) != null || !stringValues(value.get("queryTerms")).isEmpty();
    }

    private boolean hasSqlMetadataEvidence(Map<String, Object> value) {
        return value.get("sourceEvidence") instanceof Iterable<?>;
    }

    private boolean isEnterpriseMetadataSearch(String toolName) {
        String value = text(toolName);
        if (value == null) {
            return false;
        }
        value = value.toLowerCase(Locale.ROOT);
        return "enterprise_metadata_search".equals(value)
            || value.endsWith("_enterprise_metadata_search");
    }

    private void copyFirstText(Map<String, Object> target,
                               String targetKey,
                               Map<String, Object> source,
                               String... sourceKeys) {
        putText(target, targetKey, firstText(source, sourceKeys));
    }

    private String firstText(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            String result = text(value.get(key));
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private void addTexts(Set<String> target, Object value) {
        for (String item : stringValues(value)) {
            addText(target, item);
        }
    }

    private void addText(Set<String> target, Object value) {
        String item = text(value);
        if (item != null && target.size() < MAX_TERMS) {
            target.add(item);
        }
    }

    private List<String> stringValues(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            for (Object item : iterable) {
                String text = text(item);
                if (text != null) {
                    result.add(text);
                }
            }
            return List.copyOf(result);
        }
        String text = text(value);
        return text == null ? List.of() : List.of(text);
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isBlank() ? null : result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
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

    private record EvidenceProjection(
        List<Map<String, Object>> tables,
        int tableCount,
        int fieldCount
    ) {
    }
}
