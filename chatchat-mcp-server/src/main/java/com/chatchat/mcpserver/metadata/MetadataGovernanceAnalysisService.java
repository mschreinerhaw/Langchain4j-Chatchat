package com.chatchat.mcpserver.metadata;

import com.chatchat.mcpserver.sql.SqlMetadataSearchService;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic metadata-governance analysis over the maintained enterprise catalog.
 * This service never executes the supplied DDL.
 */
@Service
public class MetadataGovernanceAnalysisService {

    public static final String ANNOTATION_SCHEMA_VERSION = "metadata_ddl_annotation.v1";
    public static final String COMPARISON_SCHEMA_VERSION = "metadata_standard_comparison.v1";

    private final EnterpriseMetadataCatalog catalog;
    private final SqlMetadataSearchService sqlMetadataSearchService;
    private final MetadataGovernancePolicyService policyService;

    public MetadataGovernanceAnalysisService(EnterpriseMetadataCatalog catalog,
                                             SqlMetadataSearchService sqlMetadataSearchService,
                                             MetadataGovernancePolicyService policyService) {
        this.catalog = catalog;
        this.sqlMetadataSearchService = sqlMetadataSearchService;
        this.policyService = policyService;
    }

    public Map<String, Object> annotateDdl(String ddl) {
        ParsedTable table = parseDdl(ddl);
        CatalogIndex index = CatalogIndex.of(catalog.records(), contract());
        List<Map<String, Object>> annotations = table.columns().stream()
            .map(column -> annotate(column, index))
            .toList();
        long matched = annotations.stream()
            .filter(value -> value.get("standardField") != null)
            .count();
        return mapOf(
            "schemaVersion", ANNOTATION_SCHEMA_VERSION,
            "success", true,
            "executionStatus", "NOT_EXECUTED",
            "table", table.name(),
            "columnCount", annotations.size(),
            "standardFieldMatchedCount", matched,
            "standardFieldUnmatchedCount", annotations.size() - matched,
            "columns", annotations,
            "catalogEvidence", catalogEvidence(index),
            "factBoundary", "maintained_enterprise_metadata_catalog"
        );
    }

    public Map<String, Object> compareDdl(String ddl) {
        ParsedTable table = parseDdl(ddl);
        return compare(table, "ddl", Map.of("executionStatus", "NOT_EXECUTED"));
    }

    public Map<String, Object> compareRegisteredTable(Map<String, Object> tableRequest) {
        Map<String, Object> request = tableRequest == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(tableRequest);
        request.put("includeColumns", true);
        request.put("detailLimit", 2);
        Map<String, Object> searchResult = sqlMetadataSearchService.search(request);
        List<Map<String, Object>> tables = maps(searchResult.get("topTables"));
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("No registered physical table matched the request");
        }
        Map<String, Object> selected = tables.get(0);
        List<ParsedColumn> columns = new ArrayList<>();
        for (Map<String, Object> value : maps(selected.get("columns"))) {
            columns.add(new ParsedColumn(
                text(value.get("name")),
                firstText(text(value.get("columnType")), text(value.get("dataType"))),
                text(value.get("comment")),
                booleanValue(value.get("nullable")),
                text(value.get("defaultValue"))
            ));
        }
        Map<String, Object> location = objectMap(selected.get("location"));
        String tableName = firstText(text(location.get("qualifiedName")), text(location.get("tableName")),
            text(request.get("tableName")));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Matched table has no retrievable column metadata");
        }
        return compare(new ParsedTable(tableName, List.copyOf(columns)), "registered_table", mapOf(
            "sqlMetadataSchemaVersion", searchResult.get("schemaVersion"),
            "searchRequestId", searchResult.get("searchRequestId"),
            "location", location
        ));
    }

    private Map<String, Object> compare(ParsedTable table, String source, Map<String, Object> sourceEvidence) {
        CatalogIndex index = CatalogIndex.of(catalog.records(), contract());
        List<Map<String, Object>> columns = new ArrayList<>();
        List<Map<String, Object>> differences = new ArrayList<>();
        for (ParsedColumn column : table.columns()) {
            Map<String, Object> annotation = annotate(column, index);
            columns.add(annotation);
            @SuppressWarnings("unchecked")
            Map<String, Object> standardField = (Map<String, Object>) annotation.get("standardField");
            if (standardField == null) {
                differences.add(difference(column.name(), "STANDARD_FIELD_MISSING",
                    column.name(), null));
            } else {
                compareFieldProperties(column, standardField, differences);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> dictionaries =
                    (List<Map<String, Object>>) annotation.get("standardDictionaries");
                String valueRangeKey = contract().getValueRangeAttribute();
                if (text(standardField.get(valueRangeKey)) != null && dictionaries.isEmpty()) {
                    differences.add(difference(column.name(), "DICTIONARY_MAPPING_MISSING",
                        standardField.get(valueRangeKey), null));
                }
            }
            @SuppressWarnings("unchecked")
            List<String> unmatchedTerms = (List<String>) annotation.get("unmatchedNameTerms");
            for (String term : unmatchedTerms) {
                differences.add(difference(column.name(), "TERM_NOT_STANDARD", term, null));
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        differences.forEach(value -> counts.merge(String.valueOf(value.get("severity")), 1L, Long::sum));
        return mapOf(
            "schemaVersion", COMPARISON_SCHEMA_VERSION,
            "success", true,
            "analysisSource", source,
            "table", table.name(),
            "columnCount", columns.size(),
            "differenceCount", differences.size(),
            "conforms", differences.isEmpty(),
            "severityCounts", counts,
            "columns", columns,
            "differences", differences,
            "sourceEvidence", sourceEvidence,
            "catalogEvidence", catalogEvidence(index),
            "factBoundary", "physical_schema_and_maintained_enterprise_metadata_catalog"
        );
    }

    private void compareFieldProperties(ParsedColumn column, Map<String, Object> standard,
                                        List<Map<String, Object>> differences) {
        String expectedName = text(standard.get("technicalName"));
        if (expectedName != null && !normalize(column.name()).equals(normalize(expectedName))) {
            differences.add(difference(column.name(), "TECHNICAL_NAME_MISMATCH",
                column.name(), expectedName));
        }
        String expectedType = text(standard.get(contract().getDataTypeAttribute()));
        if (expectedType != null && !typeCompatible(column.dataType(), expectedType,
            text(standard.get(contract().getLengthAttribute())),
            text(standard.get(contract().getPrecisionAttribute())))) {
            differences.add(difference(column.name(), "DATA_TYPE_MISMATCH",
                column.dataType(), expectedTypeWithSize(standard)));
        }
        Boolean expectedNullable = nullable(text(standard.get(contract().getNullableAttribute())));
        if (expectedNullable != null && column.nullable() != null
            && !expectedNullable.equals(column.nullable())) {
            differences.add(difference(column.name(), "NULLABILITY_MISMATCH",
                column.nullable(), expectedNullable));
        }
    }

    private Map<String, Object> annotate(ParsedColumn column, CatalogIndex index) {
        Match field = bestField(column, index.fields());
        List<Match> terms = matchTerms(column.name(), index.terms());
        List<String> matchedTokens = terms.stream().map(Match::token).toList();
        List<String> unmatchedTokens = nameTokens(column.name()).stream()
            .filter(token -> !matchedTokens.contains(normalize(token)))
            .toList();
        List<Map<String, Object>> dictionaries = matchingDictionaries(column, field, index.dictionaries());
        return mapOf(
            "physical", mapOf(
                "name", column.name(),
                "dataType", column.dataType(),
                "comment", column.comment(),
                "nullable", column.nullable(),
                "defaultValue", column.defaultValue()
            ),
            "standardField", field == null ? null : evidence(field.record(), field.score(), field.reason()),
            "standardTerms", terms.stream()
                .map(match -> evidence(match.record(), match.score(), match.reason()))
                .toList(),
            "standardDictionaries", dictionaries,
            "unmatchedNameTerms", unmatchedTokens,
            "confidence", confidence(field, unmatchedTokens),
            "annotationStatus", field == null ? "UNMATCHED" : "ANNOTATED"
        );
    }

    private Match bestField(ParsedColumn column, List<EnterpriseMetadataRecord> fields) {
        return fields.stream()
            .map(record -> scoreField(column, record))
            .filter(match -> match.score() >= governance().getMinimumFieldScore())
            .max(Comparator.comparingDouble(Match::score))
            .orElse(null);
    }

    private Match scoreField(ParsedColumn column, EnterpriseMetadataRecord record) {
        String columnName = normalize(column.name());
        Set<String> aliases = aliases(record);
        if (aliases.contains(columnName)) {
            return new Match(record, governance().getExactNameScore(), "exact_name_or_alias", null);
        }
        String comment = normalize(column.comment());
        if (!comment.isEmpty() && aliases.contains(comment)) {
            return new Match(record, governance().getExactCommentScore(), "exact_comment", null);
        }
        Set<String> columnTokens = new LinkedHashSet<>(nameTokens(column.name()));
        double best = aliases.stream()
            .map(this::nameTokens)
            .map(Set::copyOf)
            .mapToDouble(aliasTokens -> jaccard(columnTokens, aliasTokens))
            .max()
            .orElse(0d);
        if (!comment.isEmpty() && (normalize(record.name()).contains(comment)
            || comment.contains(normalize(record.name())))) {
            best = Math.max(best, governance().getPartialCommentScore());
        }
        return new Match(record, best, "token_similarity", null);
    }

    private List<Match> matchTerms(String columnName, List<EnterpriseMetadataRecord> terms) {
        List<Match> matches = new ArrayList<>();
        for (String token : nameTokens(columnName)) {
            terms.stream()
                .filter(record -> aliases(record).contains(token))
                .findFirst()
                .ifPresent(record -> matches.add(new Match(
                    record, governance().getExactNameScore(), "name_token_exact", token)));
        }
        return List.copyOf(matches);
    }

    private List<Map<String, Object>> matchingDictionaries(ParsedColumn column, Match field,
                                                           List<EnterpriseMetadataRecord> dictionaries) {
        Set<String> context = new LinkedHashSet<>(nameTokens(column.name()));
        context.add(normalize(column.name()));
        context.addAll(nameTokens(column.comment()));
        context.add(normalize(column.comment()));
        if (field != null) {
            context.addAll(nameTokens(field.record().name()));
            context.addAll(nameTokens(field.record().technicalName()));
            String valueRangeKey = contract().getValueRangeAttribute();
            context.addAll(nameTokens(text(field.record().attributes().get(valueRangeKey))));
            context.add(normalize(field.record().technicalName()));
            context.add(normalize(text(field.record().attributes().get(valueRangeKey))));
        }
        context.remove("");
        return dictionaries.stream()
            .filter(record -> aliases(record).stream().anyMatch(context::contains))
            .limit(governance().getMaximumDictionaryMatches())
            .map(record -> evidence(
                record, governance().getDictionaryContextScore(), "column_or_standard_context"))
            .toList();
    }

    private Map<String, Object> evidence(EnterpriseMetadataRecord record, double score, String reason) {
        Map<String, Object> value = new LinkedHashMap<>(record.toMap());
        value.put("matchScore", Math.round(score * 1000d) / 1000d);
        value.put("matchReason", reason);
        return value;
    }

    private Map<String, Object> catalogEvidence(CatalogIndex index) {
        return mapOf(
            "standardFieldCount", index.fields().size(),
            "standardTermCount", index.terms().size(),
            "standardDictionaryItemCount", index.dictionaries().size(),
            "catalogStatus", catalog.status()
        );
    }

    private ParsedTable parseDdl(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            throw new IllegalArgumentException("ddl is required");
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(ddl);
            if (!(statement instanceof CreateTable createTable)) {
                throw new IllegalArgumentException("Only one CREATE TABLE statement is supported");
            }
            List<ColumnDefinition> definitions = createTable.getColumnDefinitions();
            if (definitions == null || definitions.isEmpty()) {
                throw new IllegalArgumentException("CREATE TABLE contains no columns");
            }
            List<ParsedColumn> columns = definitions.stream().map(this::parsedColumn).toList();
            return new ParsedTable(createTable.getTable().getFullyQualifiedName(), columns);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to parse CREATE TABLE DDL: " + ex.getMessage(), ex);
        }
    }

    private ParsedColumn parsedColumn(ColumnDefinition definition) {
        List<String> specs = definition.getColumnSpecs() == null ? List.of() : definition.getColumnSpecs();
        String comment = specValue(specs, "COMMENT");
        String defaultValue = specValue(specs, "DEFAULT");
        boolean notNull = adjacent(specs, "NOT", "NULL");
        boolean explicitNull = specs.stream().anyMatch(value -> "NULL".equalsIgnoreCase(value));
        Boolean nullable = null;
        if (notNull) {
            nullable = Boolean.FALSE;
        } else if (explicitNull) {
            nullable = Boolean.TRUE;
        }
        return new ParsedColumn(
            unquote(definition.getColumnName()),
            definition.getColDataType() == null ? null : definition.getColDataType().toString(),
            unquote(comment),
            nullable,
            unquote(defaultValue)
        );
    }

    private String specValue(List<String> specs, String keyword) {
        for (int i = 0; i + 1 < specs.size(); i++) {
            if (keyword.equalsIgnoreCase(specs.get(i))) return specs.get(i + 1);
        }
        return null;
    }

    private boolean adjacent(List<String> values, String left, String right) {
        for (int i = 0; i + 1 < values.size(); i++) {
            if (left.equalsIgnoreCase(values.get(i)) && right.equalsIgnoreCase(values.get(i + 1))) return true;
        }
        return false;
    }

    private boolean typeCompatible(String actual, String expected, String length, String precision) {
        if (actual == null || expected == null) return true;
        String normalizedActual = normalizeType(actual);
        String normalizedExpected = normalizeType(expectedWithSize(expected, length, precision));
        if (normalizedActual.equals(normalizedExpected)) return true;
        return baseType(normalizedActual).equals(baseType(normalizedExpected))
            && (!normalizedExpected.contains("(") || normalizedActual.equals(normalizedExpected));
    }

    private String expectedTypeWithSize(Map<String, Object> standard) {
        return expectedWithSize(
            text(standard.get(contract().getDataTypeAttribute())),
            text(standard.get(contract().getLengthAttribute())),
            text(standard.get(contract().getPrecisionAttribute())));
    }

    private String expectedWithSize(String type, String length, String precision) {
        if (type == null || type.contains("(")) return type;
        if (precision != null && length != null) return type + "(" + length + "," + precision + ")";
        if (length != null) return type + "(" + length + ")";
        return type;
    }

    private String normalizeType(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String baseType(String value) {
        int index = value.indexOf('(');
        return index < 0 ? value : value.substring(0, index);
    }

    private Boolean nullable(String value) {
        if (value == null) return null;
        String normalized = normalize(value);
        if (normalizedValues(governance().getNullableTrueValues()).contains(normalized)) return true;
        if (normalizedValues(governance().getNullableFalseValues()).contains(normalized)) return false;
        return null;
    }

    private double confidence(Match field, List<String> unmatchedTokens) {
        if (field == null) return 0d;
        double penalty = Math.min(
            governance().getMaximumUnmatchedPenalty(),
            unmatchedTokens.size() * governance().getUnmatchedTokenPenalty());
        return Math.round(Math.max(0d, field.score() - penalty) * 1000d) / 1000d;
    }

    private Set<String> aliases(EnterpriseMetadataRecord record) {
        Set<String> aliases = new LinkedHashSet<>();
        addAlias(aliases, record.name());
        addAlias(aliases, record.technicalName());
        addAlias(aliases, text(record.attributes().get(contract().getEnglishNameAttribute())));
        addAlias(aliases, text(record.attributes().get(contract().getAbbreviationAttribute())));
        addAlias(aliases, text(record.attributes().get(contract().getDictionaryEnglishNameAttribute())));
        addAlias(aliases, text(record.attributes().get(contract().getDictionaryIdAttribute())));
        return aliases;
    }

    private void addAlias(Set<String> aliases, String value) {
        String normalized = normalize(value);
        if (!normalized.isEmpty()) aliases.add(normalized);
    }

    private List<String> nameTokens(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "_").split("_"))
            .stream().map(this::normalize).filter(token -> !token.isBlank()).toList();
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0d;
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private String normalize(String value) {
        return value == null ? "" : unquote(value).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private String unquote(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '\'' && last == '\'') || (first == '"' && last == '"')
            || (first == '`' && last == '`') || (first == '[' && last == ']')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private Map<String, Object> difference(String column, String code,
                                           Object actual, Object expected) {
        return mapOf(
            "column", column,
            "code", code,
            "severity", requiredRule(governance().getDifferenceSeverities(), code, "severity"),
            "actual", actual,
            "expected", expected,
            "message", requiredRule(governance().getDifferenceMessages(), code, "message")
        );
    }

    private MetadataGovernancePolicy.MetadataContract contract() {
        return policyService.current().getMetadataContract();
    }

    private MetadataGovernancePolicy.ComparisonPolicy governance() {
        return policyService.current().getComparison();
    }

    private Set<String> normalizedValues(List<String> values) {
        if (values == null) return Set.of();
        return values.stream().map(this::normalize).filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String requiredRule(Map<String, String> rules, String code, String kind) {
        String value = rules == null ? null : rules.get(code);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing metadata governance " + kind + " for " + code);
        }
        return value;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) item).toList();
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            Object value = values[index + 1];
            if (value != null) result.put(String.valueOf(values[index]), value);
        }
        return result;
    }

    private record ParsedTable(String name, List<ParsedColumn> columns) {
    }

    private record ParsedColumn(String name, String dataType, String comment, Boolean nullable,
                                String defaultValue) {
    }

    private record Match(EnterpriseMetadataRecord record, double score, String reason, String token) {
    }

    private record CatalogIndex(List<EnterpriseMetadataRecord> fields,
                                List<EnterpriseMetadataRecord> terms,
                                List<EnterpriseMetadataRecord> dictionaries) {
        static CatalogIndex of(List<EnterpriseMetadataRecord> records,
                               MetadataGovernancePolicy.MetadataContract contract) {
            List<EnterpriseMetadataRecord> safe = records == null ? List.of() : records;
            return new CatalogIndex(
                byType(safe, contract.getFieldType()),
                byType(safe, contract.getTermType()),
                byType(safe, contract.getDictionaryType())
            );
        }

        private static List<EnterpriseMetadataRecord> byType(List<EnterpriseMetadataRecord> records,
                                                              String type) {
            return records.stream().filter(record -> type.equals(record.metadataType())).toList();
        }
    }
}
