package com.chatchat.mcpserver.metadata;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves a complete table or field draft into field-scoped enterprise metadata evidence.
 */
@Service
public class EnterpriseMetadataMatchingService {

    public static final String SCHEMA_VERSION = "enterprise_metadata_field_discovery.v1";
    private static final List<String> DEFAULT_MATCH_STRATEGIES =
        List.of("ENGLISH_NAME", "CHINESE_NAME", "ALIAS", "SEMANTIC");

    private final MetadataEvidenceProvider evidenceProvider;
    private final MetadataGovernanceAnalysisService governanceAnalysisService;
    private final MetadataGovernancePolicyService policyService;
    private final EnterpriseMetadataProperties properties;
    private final EnterpriseMetadataCatalog catalog;

    public EnterpriseMetadataMatchingService(
        MetadataEvidenceProvider evidenceProvider,
        MetadataGovernanceAnalysisService governanceAnalysisService,
        MetadataGovernancePolicyService policyService,
        EnterpriseMetadataProperties properties,
        EnterpriseMetadataCatalog catalog
    ) {
        this.evidenceProvider = evidenceProvider;
        this.governanceAnalysisService = governanceAnalysisService;
        this.policyService = policyService;
        this.properties = properties;
        this.catalog = catalog;
    }

    public Map<String, Object> match(Map<String, Object> input) {
        Map<String, Object> request = input == null ? Map.of() : input;
        String requestId = firstText(text(request.get("requestId")), UUID.randomUUID().toString());
        String purpose = firstText(text(request.get("purpose")), "FIELD_METADATA_REVIEW");
        List<String> strategies = strings(request.get("matchStrategy"));
        if (strategies.isEmpty()) {
            strategies = DEFAULT_MATCH_STRATEGIES;
        }
        int candidateLimit = requestedLimit(request.containsKey("candidateLimitPerType")
            ? request.get("candidateLimitPerType") : request.get("limit"));
        ResolvedSchema schema = resolveSchema(request);
        List<MetadataEvidenceProviderProtocol.FieldQuery> providerFields =
            new ArrayList<>();
        for (int index = 0; index < schema.fields().size(); index++) {
            providerFields.add(providerFieldQuery(
                "field-" + (index + 1), schema.fields().get(index), request));
        }
        MetadataEvidenceProviderProtocol.MatchRequest providerRequest =
            new MetadataEvidenceProviderProtocol.MatchRequest(
                requestId,
                firstText(text(request.get("matchMode")), "FIELD_MAPPING"),
                providerFields,
                requiredTypes(),
                strings(request.get("statuses")),
                strings(request.get("scenarios")),
                candidateLimit
            );
        MetadataEvidenceProviderProtocol.MatchResponse providerResponse =
            evidenceProvider.match(providerRequest);
        Map<String, MetadataEvidenceProviderProtocol.FieldResult> providerResults =
            new LinkedHashMap<>();
        providerResponse.results().forEach(result ->
            providerResults.put(result.fieldId(), result));

        List<Map<String, Object>> fieldMatches = new ArrayList<>();
        List<Map<String, Object>> evidenceObjects = new ArrayList<>();
        for (int index = 0; index < schema.fields().size(); index++) {
            ResolvedField field = schema.fields().get(index);
            String fieldRef = "field-" + (index + 1);
            FieldDiscovery discovery = discoverField(
                fieldRef,
                field,
                providerFields.get(index),
                providerResults.getOrDefault(
                    fieldRef,
                    new MetadataEvidenceProviderProtocol.FieldResult(fieldRef, List.of())),
                strategies
            );
            fieldMatches.add(discovery.protocol());
            evidenceObjects.addAll(discovery.evidenceObjects());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", SCHEMA_VERSION);
        response.put("success", true);
        response.put("requestId", requestId);
        response.put("purpose", purpose);
        response.put("targetObject", targetObject(request, schema));
        response.put("sourceSchema", mapOf(
            "mode", schema.mode(),
            "table", schema.table(),
            "fieldCount", schema.fields().size(),
            "fields", schema.fields().stream().map(ResolvedField::toMap).toList(),
            "sourceEvidence", schema.sourceEvidence()
        ));
        response.put("fieldMatches", List.copyOf(fieldMatches));
        response.put("evidenceObjects", List.copyOf(evidenceObjects));
        response.put("providerExchange", mapOf(
            "providerType", evidenceProvider.getClass().getSimpleName(),
            "request", providerRequest.toMap(),
            "response", providerResponse.toMap()
        ));
        response.put("coverage", mapOf(
            "inputFieldCount", schema.fields().size(),
            "processedFieldCount", fieldMatches.size(),
            "allFieldsProcessed", fieldMatches.size() == schema.fields().size(),
            "requiredMetadataTypes", requiredTypes(),
            "perFieldTypeRetrieval", true
        ));
        response.put("evidenceCoverage", policyService.evidenceCoverage());
        if (!schema.comparisonEvidence().isEmpty()) {
            response.put("fieldComparisonEvidence", schema.comparisonEvidence());
        }
        response.put("reviewContract", mapOf(
            "reviewRequired", true,
            "decisionScope", "PER_FIELD",
            "candidateReturnPolicy", "ALL_RETRIEVED_CANDIDATES",
            "reasoningCandidateSelection", mapOf(
                "strategy", "HIGHEST_SCORE",
                "maximumSelectedPerFieldAndMetadataType", 1,
                "tieBreaker", "PROVIDER_ORDER"
            ),
            "allowedDecisions", List.of(
                "REUSE", "REVIEW", "CREATE_STANDARD_CANDIDATE", "REJECT"),
            "evidenceContract", "evidence_object_v1",
            "factBoundary", "returned_field_scoped_enterprise_metadata_evidence_only",
            "instruction", "Review each fieldMatches[].analysis and its linked evidenceObjects before deciding whether to reuse a standard field."
        ));
        return response;
    }

    private FieldDiscovery discoverField(
        String fieldRef,
        ResolvedField field,
        MetadataEvidenceProviderProtocol.FieldQuery providerField,
        MetadataEvidenceProviderProtocol.FieldResult providerResult,
        List<String> strategies
    ) {
        Map<String, List<Map<String, Object>>> candidatesByType = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> evidenceByType = new LinkedHashMap<>();
        List<Map<String, Object>> allEvidence = new ArrayList<>();

        for (String type : requiredTypes()) {
            List<Map<String, Object>> candidates = providerResult.candidates().stream()
                .filter(candidate -> type.equals(candidate.metadataType()))
                .map(value -> candidate(
                    fieldRef,
                    field,
                    type,
                    value.metadata(),
                    value.evidenceObject(),
                    value.matchResult()
                ))
                .sorted(candidateComparator())
                .toList();
            List<Map<String, Object>> scopedEvidence = candidates.stream()
                .map(candidate -> objectMap(candidate.get("evidence")))
                .filter(value -> !value.isEmpty())
                .toList();
            candidatesByType.put(type, candidates.stream()
                .map(this::withoutEmbeddedEvidence).toList());
            evidenceByType.put(type, scopedEvidence);
            allEvidence.addAll(scopedEvidence);
        }

        String fieldType = contract().getFieldType();
        List<Map<String, Object>> standardFields =
            candidatesByType.getOrDefault(fieldType, List.of());
        Map<String, Object> analysis = preliminaryAnalysis(standardFields);
        Map<String, Object> protocol = mapOf(
            "fieldRef", fieldRef,
            "input", field.toMap(),
            "searchPlan", mapOf(
                "query", String.join(" ", providerField.searchTokens()),
                "tokens", providerField.tokens().stream()
                    .map(MetadataEvidenceProviderProtocol.SearchToken::toMap).toList(),
                "terms", providerField.searchTokens(),
                "dictionaryTerms", providerField.dictionaryTerms(),
                "standardField", providerField.standardField(),
                "domain", providerField.domain(),
                "matchStrategy", strategies,
                "metadataTypes", requiredTypes(),
                "providerRequestFieldId", providerField.fieldId()
            ),
            "matchesByType", candidatesByType,
            "standardFields", standardFields,
            "termRoots", candidatesByType.getOrDefault(contract().getTermType(), List.of()),
            "dictionaries", candidatesByType.getOrDefault(contract().getDictionaryType(), List.of()),
            "evidenceByType", evidenceByType,
            "analysis", analysis,
            "decisionRequired", true
        );
        return new FieldDiscovery(protocol, List.copyOf(allEvidence));
    }

    private Map<String, Object> candidate(
        String fieldRef,
        ResolvedField field,
        String metadataType,
        Map<String, Object> record,
        Map<String, Object> sourceEvidence,
        Map<String, Object> providerMatchResult
    ) {
        MatchClassification classification = classify(field, record);
        Boolean compatible = dataTypeCompatible(field.dataType(),
            text(record.get(contract().getDataTypeAttribute())));
        String level = Boolean.FALSE.equals(compatible) && classification.exact()
            ? "CONFLICT" : classification.level();
        String recommendation;
        if (!contract().getFieldType().equals(metadataType)) {
            recommendation = "EVIDENCE_ONLY";
        } else if ("CONFLICT".equals(level)) {
            recommendation = "DO_NOT_REUSE";
        } else if ("EXACT".equals(level) && !Boolean.FALSE.equals(compatible)) {
            recommendation = "REUSE";
        } else {
            recommendation = "REVIEW";
        }
        Map<String, Object> evidence = scopedEvidence(fieldRef, field, sourceEvidence);
        return mapOf(
            "metadataType", metadataType,
            "id", record.get("id"),
            "name", record.get("name"),
            "technicalName", record.get("technicalName"),
            "matchType", classification.type(),
            "matchLevel", level,
            "score", record.getOrDefault("relevanceScore", 0.0D),
            "dataTypeCompatible", compatible,
            "recommendation", recommendation,
            "evidenceId", evidence.get("evidenceId"),
            "providerMatchResult", providerMatchResult,
            "metadata", record,
            "evidence", evidence
        );
    }

    private MatchClassification classify(ResolvedField field, Map<String, Object> candidate) {
        String english = normalize(field.fieldName());
        String chinese = normalize(field.fieldCnName());
        String primaryName = normalize(text(candidate.get("name")));
        String technicalName = normalize(text(candidate.get("technicalName")));
        String englishName = normalize(text(candidate.get(contract().getEnglishNameAttribute())));
        String abbreviation = normalize(text(candidate.get(contract().getAbbreviationAttribute())));
        Set<String> aliases = new LinkedHashSet<>(List.of(
            primaryName, technicalName, englishName, abbreviation));
        aliases.remove("");
        if (!english.isEmpty() && (english.equals(technicalName) || english.equals(englishName))) {
            return new MatchClassification("EXACT_ENGLISH", "EXACT", true);
        }
        if (!chinese.isEmpty() && chinese.equals(primaryName)) {
            return new MatchClassification("EXACT_CHINESE", "EXACT", true);
        }
        if ((!english.isEmpty() && aliases.contains(english))
            || (!chinese.isEmpty() && aliases.contains(chinese))) {
            return new MatchClassification("ALIAS", "SYNONYM", true);
        }
        Set<String> targetTokens = tokens(String.join(" ",
            safe(field.fieldName()), safe(field.fieldCnName()), safe(field.description())));
        Set<String> candidateTokens = tokens(String.join(" ",
            safe(text(candidate.get("name"))),
            safe(text(candidate.get("technicalName"))),
            safe(text(candidate.get("description"))),
            safe(text(candidate.get(contract().getEnglishNameAttribute()))),
            safe(text(candidate.get(contract().getAbbreviationAttribute())))));
        Set<String> overlap = new LinkedHashSet<>(targetTokens);
        overlap.retainAll(candidateTokens);
        if (!overlap.isEmpty()) {
            return new MatchClassification("SEMANTIC", "SEMANTIC", false);
        }
        return new MatchClassification("RELATED", "RELATED", false);
    }

    private Map<String, Object> preliminaryAnalysis(List<Map<String, Object>> standardFields) {
        if (standardFields.isEmpty()) {
            return mapOf(
                "canReuse", false,
                "recommendedField", null,
                "recommendation", "CREATE_STANDARD_CANDIDATE",
                "reason", List.of("No maintained standard-field candidate was returned"),
                "risk", List.of("Creating a physical field without an approved standard requires governance review")
            );
        }
        Map<String, Object> recommended = standardFields.get(0);
        boolean reusable = "REUSE".equals(recommended.get("recommendation"));
        List<String> reasons = new ArrayList<>();
        reasons.add("Best candidate match level is " + recommended.get("matchLevel"));
        if (recommended.get("matchType") != null) {
            reasons.add("Match evidence type is " + recommended.get("matchType"));
        }
        if (!Boolean.FALSE.equals(recommended.get("dataTypeCompatible"))) {
            reasons.add("Requested and standard data types are compatible or unspecified");
        }
        List<String> risks = new ArrayList<>();
        if (standardFields.size() > 1) {
            risks.add("Multiple standard-field candidates require model or human disambiguation");
        }
        if (Boolean.FALSE.equals(recommended.get("dataTypeCompatible"))) {
            risks.add("Best candidate data type conflicts with the requested field");
        }
        return mapOf(
            "canReuse", reusable,
            "recommendedField", mapOf(
                "id", recommended.get("id"),
                "name", recommended.get("name"),
                "technicalName", recommended.get("technicalName"),
                "evidenceId", recommended.get("evidenceId")
            ),
            "recommendation", reusable ? "REUSE" : "REVIEW",
            "reason", reasons,
            "risk", risks
        );
    }

    private ResolvedSchema resolveSchema(Map<String, Object> request) {
        String ddl = text(request.get("ddl"));
        String tableName = text(request.get("tableName"));
        List<Map<String, Object>> explicitFields = maps(request.get("fields"));
        int modes = (ddl == null ? 0 : 1) + (tableName == null ? 0 : 1)
            + (explicitFields.isEmpty() ? 0 : 1);
        if (modes != 1) {
            throw new IllegalArgumentException(
                "Supply exactly one source: ddl, tableName, or non-empty fields");
        }
        if (ddl != null) {
            Map<String, Object> annotation = governanceAnalysisService.annotateDdl(ddl);
            return schemaFromGovernance("DDL", text(annotation.get("table")), annotation);
        }
        if (tableName != null) {
            Map<String, Object> comparison =
                governanceAnalysisService.compareRegisteredTable(request);
            return schemaFromGovernance("REGISTERED_TABLE", text(comparison.get("table")), comparison);
        }
        List<ResolvedField> fields = explicitFields.stream().map(this::explicitField).toList();
        return new ResolvedSchema(
            "FIELD_LIST",
            firstText(text(objectMap(request.get("targetObject")).get("name")), "field_draft"),
            fields,
            fieldListSourceEvidence(request),
            Map.of()
        );
    }

    private Map<String, Object> fieldListSourceEvidence(Map<String, Object> request) {
        Map<String, Object> evidence = objectMap(request.get("schemaEvidence"));
        if (!evidence.isEmpty()) {
            return evidence;
        }
        return Map.of("executionStatus", "NOT_EXECUTED");
    }

    private ResolvedSchema schemaFromGovernance(
        String mode, String table, Map<String, Object> governance
    ) {
        List<ResolvedField> fields = maps(governance.get("columns")).stream()
            .map(value -> explicitField(objectMap(value.get("physical"))))
            .toList();
        return new ResolvedSchema(
            mode, table, fields,
            mapOf(
                "schemaVersion", governance.get("schemaVersion"),
                "executionStatus", governance.getOrDefault("executionStatus", "NOT_EXECUTED"),
                "analysisSource", governance.get("analysisSource"),
                "sourceEvidence", governance.get("sourceEvidence")
            ),
            comparisonEvidence(governance)
        );
    }

    private Map<String, Object> comparisonEvidence(Map<String, Object> governance) {
        if (governance == null || governance.isEmpty()
            || governance.get("conforms") == null) {
            return Map.of();
        }
        return mapOf(
            "scope", "FIELD_METADATA_COMPARISON",
            "differenceCount", governance.get("differenceCount"),
            "severityCounts", governance.get("severityCounts"),
            "differences", governance.get("differences"),
            "factBoundary", governance.get("factBoundary")
        );
    }

    private ResolvedField explicitField(Map<String, Object> value) {
        String fieldName = firstText(text(value.get("fieldName")), text(value.get("name")));
        String fieldCnName = firstText(
            text(value.get("fieldCnName")), text(value.get("chineseName")),
            text(value.get("comment")));
        String description = firstText(text(value.get("description")), text(value.get("comment")));
        if (fieldName == null && fieldCnName == null) {
            throw new IllegalArgumentException(
                "Each field requires fieldName/name or fieldCnName/chineseName");
        }
        return new ResolvedField(
            fieldName, fieldCnName, text(value.get("dataType")), description,
            value.get("nullable"), text(value.get("defaultValue")),
            firstText(text(value.get("domain")), text(value.get("businessDomain")))
        );
    }

    private MetadataEvidenceProviderProtocol.FieldQuery providerFieldQuery(
        String fieldId, ResolvedField field, Map<String, Object> request
    ) {
        Set<String> searchValues = new LinkedHashSet<>(searchTerms(field));
        List<MetadataEvidenceProviderProtocol.SearchToken> typedTokens =
            new ArrayList<>();
        addTypedToken(typedTokens, searchValues, "CN", field.fieldCnName());
        for (String token : technicalTokens(field.fieldName())) {
            addTypedToken(typedTokens, searchValues, "EN", token);
        }

        String searchable = normalize(String.join(" ",
            safe(field.fieldName()), safe(field.fieldCnName()),
            safe(field.description())));
        for (EnterpriseMetadataRecord term : catalog.records().stream()
            .filter(record -> contract().getTermType().equals(record.metadataType()))
            .toList()) {
            if (!recordAliases(term).stream()
                .map(this::normalize)
                .anyMatch(alias -> !alias.isEmpty() && searchable.contains(alias))) {
                continue;
            }
            addTypedToken(typedTokens, searchValues, "ROOT", term.name());
            addTypedToken(typedTokens, searchValues, "ROOT", term.technicalName());
            addTypedToken(typedTokens, searchValues, "ABBR",
                text(term.attributes().get(contract().getAbbreviationAttribute())));
        }
        List<String> abbreviations = typedTokens.stream()
            .filter(token -> "ABBR".equals(token.type()))
            .map(MetadataEvidenceProviderProtocol.SearchToken::value)
            .distinct()
            .toList();
        if (abbreviations.size() > 1) {
            addTypedToken(typedTokens, searchValues, "ABBR",
                String.join("_", abbreviations));
        }
        Set<String> targetAliases = new LinkedHashSet<>();
        addNormalized(targetAliases, field.fieldName());
        addNormalized(targetAliases, field.fieldCnName());
        addNormalized(targetAliases, field.description());
        String standardField = catalog.records().stream()
            .filter(record -> contract().getFieldType().equals(record.metadataType()))
            .filter(record -> recordAliases(record).stream()
                .map(this::normalize)
                .anyMatch(targetAliases::contains))
            .map(EnterpriseMetadataRecord::technicalName)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
        List<String> dictionaryTerms = typedTokens.stream()
            .filter(token -> "ROOT".equals(token.type()))
            .map(MetadataEvidenceProviderProtocol.SearchToken::value)
            .distinct()
            .toList();
        Map<String, Object> target = objectMap(request.get("targetObject"));
        String domain = firstText(
            field.domain(),
            text(target.get("domain")),
            text(target.get("businessDomain"))
        );
        return new MetadataEvidenceProviderProtocol.FieldQuery(
            fieldId,
            standardField,
            List.copyOf(typedTokens),
            List.copyOf(searchValues),
            dictionaryTerms,
            field.dataType(),
            domain
        );
    }

    private List<String> searchTerms(ResolvedField field) {
        Set<String> terms = new LinkedHashSet<>();
        addTerm(terms, field.fieldName());
        addTerm(terms, splitTechnicalName(field.fieldName()));
        addTerm(terms, field.fieldCnName());
        addTerm(terms, field.description());
        return List.copyOf(terms);
    }

    private List<String> technicalTokens(String value) {
        String split = splitTechnicalName(value);
        return split == null || split.isBlank()
            ? List.of()
            : List.of(split.split("\\s+"));
    }

    private void addTypedToken(
        List<MetadataEvidenceProviderProtocol.SearchToken> tokens,
        Set<String> searchValues,
        String type,
        String value
    ) {
        String normalized = text(value);
        if (normalized == null) {
            return;
        }
        boolean exists = tokens.stream().anyMatch(token ->
            type.equals(token.type()) && normalized.equalsIgnoreCase(token.value()));
        if (!exists) {
            tokens.add(new MetadataEvidenceProviderProtocol.SearchToken(type, normalized));
        }
        searchValues.add(normalized);
    }

    private List<String> recordAliases(EnterpriseMetadataRecord record) {
        List<String> aliases = new ArrayList<>();
        addTerm(aliases, record.name());
        addTerm(aliases, record.technicalName());
        addTerm(aliases, text(record.attributes().get(
            contract().getEnglishNameAttribute())));
        addTerm(aliases, text(record.attributes().get(
            contract().getAbbreviationAttribute())));
        return List.copyOf(aliases);
    }

    private void addNormalized(Set<String> values, String value) {
        String normalized = normalize(value);
        if (!normalized.isEmpty()) {
            values.add(normalized);
        }
    }

    private String splitTechnicalName(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("([\\p{Ll}\\d])([\\p{Lu}])", "$1 $2")
            .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private void addTerm(Set<String> terms, String value) {
        String normalized = text(value);
        if (normalized != null) {
            terms.add(normalized);
        }
    }

    private void addTerm(List<String> terms, String value) {
        String normalized = text(value);
        if (normalized != null && !terms.contains(normalized)) {
            terms.add(normalized);
        }
    }

    private Map<String, Object> scopedEvidence(
        String fieldRef, ResolvedField field, Map<String, Object> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> evidence = new LinkedHashMap<>(source);
        String parentId = text(source.get("evidenceId"));
        evidence.put("contractVersion", "evidence_object_v1");
        evidence.put("parentEvidenceId", parentId);
        evidence.put("evidenceId", parentId + ":" + fieldRef);
        evidence.put("subject", mapOf(
            "kind", "FIELD",
            "ref", fieldRef,
            "name", firstText(field.fieldName(), field.fieldCnName())
        ));
        return Map.copyOf(evidence);
    }

    private Map<String, Object> withoutEmbeddedEvidence(Map<String, Object> candidate) {
        Map<String, Object> result = new LinkedHashMap<>(candidate);
        result.remove("evidence");
        return Map.copyOf(result);
    }

    private Comparator<Map<String, Object>> candidateComparator() {
        return Comparator
            .comparingInt((Map<String, Object> value) -> matchRank(text(value.get("matchLevel"))))
            .thenComparing(Comparator.comparingDouble(
                (Map<String, Object> value) -> number(value.get("score"))).reversed());
    }

    private int matchRank(String level) {
        return switch (safe(level)) {
            case "EXACT" -> 0;
            case "SYNONYM" -> 1;
            case "SEMANTIC" -> 2;
            case "RELATED" -> 3;
            case "CONFLICT" -> 4;
            default -> 5;
        };
    }

    private Boolean dataTypeCompatible(String requested, String standard) {
        if (text(requested) == null || text(standard) == null) {
            return null;
        }
        return baseType(requested).equals(baseType(standard));
    }

    private String baseType(String value) {
        String normalized = safe(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        int bracket = normalized.indexOf('(');
        return bracket < 0 ? normalized : normalized.substring(0, bracket);
    }

    private Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : safe(value).toLowerCase(Locale.ROOT)
            .replaceAll("([\\p{Ll}\\d])([\\p{Lu}])", "$1 $2")
            .split("[^\\p{L}\\p{N}]+")) {
            if (!token.isBlank()) {
                result.add(token);
            }
        }
        return result;
    }

    private int requestedLimit(Object value) {
        int requested;
        if (value instanceof Number number) {
            requested = number.intValue();
        } else if (value != null) {
            try {
                requested = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("limit must be an integer", ex);
            }
        } else {
            requested = properties.getDefaultLimit();
        }
        if (requested < 1) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        return requested;
    }

    private List<String> requiredTypes() {
        List<String> required = contract().getRequiredBundle();
        if (required == null || required.isEmpty()) {
            throw new IllegalStateException("Enterprise metadata required bundle is not configured");
        }
        return List.copyOf(required);
    }

    private MetadataGovernancePolicy.MetadataContract contract() {
        return policyService.current().getMetadataContract();
    }

    private Map<String, Object> targetObject(
        Map<String, Object> request, ResolvedSchema schema
    ) {
        Map<String, Object> supplied = objectMap(request.get("targetObject"));
        return mapOf(
            "type", firstText(text(supplied.get("type")), "TABLE"),
            "name", firstText(text(supplied.get("name")), schema.table())
        );
    }

    private List<String> strings(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            iterable.forEach(item -> {
                String text = text(item);
                if (text != null) result.add(text);
            });
            return List.copyOf(result);
        }
        String single = text(value);
        return single == null ? List.of() : List.of(single);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        iterable.forEach(item -> {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        });
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0D;
    }

    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank()
            ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            if (values[index + 1] != null) {
                result.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return result;
    }

    private record ResolvedSchema(
        String mode,
        String table,
        List<ResolvedField> fields,
        Map<String, Object> sourceEvidence,
        Map<String, Object> comparisonEvidence
    ) {
    }

    private record ResolvedField(
        String fieldName,
        String fieldCnName,
        String dataType,
        String description,
        Object nullable,
        String defaultValue,
        String domain
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            if (fieldName != null) result.put("fieldName", fieldName);
            if (fieldCnName != null) result.put("fieldCnName", fieldCnName);
            if (dataType != null) result.put("dataType", dataType);
            if (description != null) result.put("description", description);
            if (nullable != null) result.put("nullable", nullable);
            if (defaultValue != null) result.put("defaultValue", defaultValue);
            if (domain != null) result.put("domain", domain);
            return Map.copyOf(result);
        }
    }

    private record MatchClassification(String type, String level, boolean exact) {
    }

    private record FieldDiscovery(
        Map<String, Object> protocol,
        List<Map<String, Object>> evidenceObjects
    ) {
    }
}
