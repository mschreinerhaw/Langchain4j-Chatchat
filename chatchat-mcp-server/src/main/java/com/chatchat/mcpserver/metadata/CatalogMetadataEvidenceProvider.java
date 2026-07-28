package com.chatchat.mcpserver.metadata;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default provider backed by the maintained enterprise metadata search index.
 */
@Service
public class CatalogMetadataEvidenceProvider implements MetadataEvidenceProvider {

    private final EnterpriseMetadataSearchService searchService;

    public CatalogMetadataEvidenceProvider(EnterpriseMetadataSearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public MetadataEvidenceProviderProtocol.MatchResponse match(
        MetadataEvidenceProviderProtocol.MatchRequest request
    ) {
        if (request == null || request.fields().isEmpty()) {
            throw new IllegalArgumentException("Metadata provider request fields are required");
        }
        List<MetadataEvidenceProviderProtocol.FieldResult> results = new ArrayList<>();
        for (MetadataEvidenceProviderProtocol.FieldQuery field : request.fields()) {
            List<MetadataEvidenceProviderProtocol.CandidateEvidence> candidates =
                new ArrayList<>();
            String query = String.join(" ", field.searchTokens());
            for (String metadataType : request.requiredMetadataTypes()) {
                Map<String, Object> search = searchService.search(
                    new EnterpriseMetadataSearchService.SearchRequest(
                        query,
                        List.of(metadataType),
                        request.statuses(),
                        request.scenarios(),
                        request.candidateLimitPerType()
                    ));
                Map<String, Map<String, Object>> evidence =
                    evidenceIndex(maps(search.get("evidenceObjects")));
                for (Map<String, Object> record : maps(search.get("results"))) {
                    candidates.add(new MetadataEvidenceProviderProtocol.CandidateEvidence(
                        metadataType,
                        record,
                        evidence.getOrDefault(recordKey(record), Map.of()),
                        providerMatch(field, record)
                    ));
                }
            }
            results.add(new MetadataEvidenceProviderProtocol.FieldResult(
                field.fieldId(), List.copyOf(candidates)));
        }
        return new MetadataEvidenceProviderProtocol.MatchResponse(
            request.requestId(), List.copyOf(results));
    }

    private Map<String, Object> providerMatch(
        MetadataEvidenceProviderProtocol.FieldQuery field,
        Map<String, Object> record
    ) {
        List<String> matchTypes = new ArrayList<>();
        String technicalName = normalized(record.get("technicalName"));
        String name = normalized(record.get("name"));
        if (!normalized(field.standardField()).isEmpty()
            && normalized(field.standardField()).equals(technicalName)) {
            matchTypes.add("STANDARD_FIELD");
        }
        for (MetadataEvidenceProviderProtocol.SearchToken token : field.tokens()) {
            String value = normalized(token.value());
            if (value.isEmpty() || (!value.equals(technicalName) && !value.equals(name))) {
                continue;
            }
            String type = switch (token.type()) {
                case "CN" -> "CN_NAME";
                case "EN" -> "EN_NAME";
                case "ROOT" -> "ROOT_MATCH";
                case "ABBR" -> "ABBR_MATCH";
                default -> "TOKEN_MATCH";
            };
            if (!matchTypes.contains(type)) {
                matchTypes.add(type);
            }
        }
        String level = matchTypes.isEmpty() ? "SEMANTIC" : "EXACT";
        Object score = record.getOrDefault("relevanceScore", 0.0D);
        return Map.of(
            "level", level,
            "matchType", List.copyOf(matchTypes),
            "score", score
        );
    }

    private Map<String, Map<String, Object>> evidenceIndex(
        List<Map<String, Object>> evidenceObjects
    ) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> evidence : evidenceObjects) {
            result.put(recordKey(objectMap(evidence.get("content"))), evidence);
        }
        return result;
    }

    private String recordKey(Map<String, Object> record) {
        return text(record.get("metadataType")) + ":" + text(record.get("id"));
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

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalized(Object value) {
        return text(value).toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", "");
    }
}
