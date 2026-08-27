package com.chatchat.mcpserver.metadata.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned boundary between the internal Metadata Resolver and evidence providers.
 */
public final class MetadataEvidenceProviderProtocol {

    public static final String REQUEST_SCHEMA_VERSION =
        "metadata_evidence_provider_request.v1";
    public static final String RESPONSE_SCHEMA_VERSION =
        "metadata_evidence_provider_response.v1";

    private MetadataEvidenceProviderProtocol() {
    }

    public record MatchRequest(
        String requestId,
        String matchMode,
        List<FieldQuery> fields,
        List<String> requiredMetadataTypes,
        List<String> statuses,
        List<String> scenarios,
        int candidateLimitPerType
    ) {
        public MatchRequest {
            fields = safe(fields);
            requiredMetadataTypes = safe(requiredMetadataTypes);
            statuses = safe(statuses);
            scenarios = safe(scenarios);
        }

        public Map<String, Object> toMap() {
            return mapOf(
                "schemaVersion", REQUEST_SCHEMA_VERSION,
                "requestId", requestId,
                "matchMode", matchMode,
                "fields", fields.stream().map(FieldQuery::toMap).toList(),
                "requiredMetadataTypes", requiredMetadataTypes,
                "statuses", statuses,
                "scenarios", scenarios,
                "candidateLimitPerType", candidateLimitPerType
            );
        }
    }

    public record FieldQuery(
        String fieldId,
        String standardField,
        List<SearchToken> tokens,
        List<String> searchTokens,
        List<String> dictionaryTerms,
        String dataType,
        String domain
    ) {
        public FieldQuery {
            tokens = safe(tokens);
            searchTokens = safe(searchTokens);
            dictionaryTerms = safe(dictionaryTerms);
        }

        public Map<String, Object> toMap() {
            return mapOf(
                "fieldId", fieldId,
                "standardField", standardField,
                "tokens", tokens.stream().map(SearchToken::toMap).toList(),
                "searchTokens", searchTokens,
                "dictionaryTerms", dictionaryTerms,
                "dataType", dataType,
                "domain", domain
            );
        }
    }

    public record SearchToken(String type, String value) {
        public Map<String, Object> toMap() {
            return mapOf("type", type, "value", value);
        }
    }

    public record MatchResponse(
        String requestId,
        List<FieldResult> results
    ) {
        public MatchResponse {
            results = safe(results);
        }

        public Map<String, Object> toMap() {
            return mapOf(
                "schemaVersion", RESPONSE_SCHEMA_VERSION,
                "requestId", requestId,
                "results", results.stream().map(FieldResult::toMap).toList()
            );
        }
    }

    public record FieldResult(
        String fieldId,
        List<CandidateEvidence> candidates
    ) {
        public FieldResult {
            candidates = safe(candidates);
        }

        public Map<String, Object> toMap() {
            return mapOf(
                "fieldId", fieldId,
                "candidates", candidates.stream().map(CandidateEvidence::toMap).toList()
            );
        }
    }

    public record CandidateEvidence(
        String metadataType,
        Map<String, Object> metadata,
        Map<String, Object> evidenceObject,
        Map<String, Object> matchResult
    ) {
        public CandidateEvidence {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            evidenceObject = evidenceObject == null ? Map.of() : Map.copyOf(evidenceObject);
            matchResult = matchResult == null ? Map.of() : Map.copyOf(matchResult);
        }

        public Map<String, Object> toMap() {
            return mapOf(
                "metadataType", metadataType,
                "tableName", metadata.get("tableName"),
                "columnName", first(
                    metadata.get("columnName"), metadata.get("technicalName")),
                "columnCnName", first(
                    metadata.get("columnCnName"), metadata.get("name")),
                "matchResult", matchResult,
                "metadata", metadata,
                "evidenceObject", evidenceObject
            );
        }
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static Object first(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            if (values[index + 1] != null) {
                result.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return Map.copyOf(result);
    }
}
