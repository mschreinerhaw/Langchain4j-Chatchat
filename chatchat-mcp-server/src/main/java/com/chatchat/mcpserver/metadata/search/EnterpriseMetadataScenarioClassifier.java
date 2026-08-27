package com.chatchat.mcpserver.metadata.search;

import com.chatchat.mcpserver.metadata.config.EnterpriseMetadataProperties;
import com.chatchat.mcpserver.metadata.catalog.EnterpriseMetadataRecord;
import com.chatchat.mcpserver.metadata.taxonomy.EnterpriseMetadataTaxonomyService;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class EnterpriseMetadataScenarioClassifier {

    private final EnterpriseMetadataProperties properties;
    private final EnterpriseMetadataTaxonomyService taxonomyService;

    public EnterpriseMetadataScenarioClassifier(EnterpriseMetadataProperties properties,
                                                EnterpriseMetadataTaxonomyService taxonomyService) {
        this.properties = properties;
        this.taxonomyService = taxonomyService;
    }

    public EnterpriseMetadataRecord classify(EnterpriseMetadataRecord record) {
        if (record == null || !properties.getScenarioClassification().isEnabled()) {
            return record;
        }
        List<ScenarioMatch> matches = matches(searchableText(record), record.metadataType());
        Map<String, Object> attributes = new LinkedHashMap<>(record.attributes());
        attributes.put("scenarioCodes", matches.stream().map(ScenarioMatch::code).toList());
        attributes.put("scenarioNames", matches.stream().map(ScenarioMatch::name).toList());
        attributes.put("scenarioDescription", matches.stream()
            .map(match -> match.name() + "：" + match.description())
            .distinct()
            .reduce((left, right) -> left + "；" + right)
            .orElse(""));
        attributes.put("semanticText", semanticText(record, matches));
        return new EnterpriseMetadataRecord(
            record.id(), record.metadataType(), record.logicalIndex(), record.name(),
            record.technicalName(), record.description(), record.status(), record.source(), attributes
        );
    }

    public List<String> classifyQuery(String query) {
        if (query == null || query.isBlank() || !properties.getScenarioClassification().isEnabled()) {
            return List.of();
        }
        return matches(query, null).stream()
            .filter(match -> !match.fallback())
            .map(ScenarioMatch::code)
            .toList();
    }

    public String enrichQuery(String query) {
        List<ScenarioMatch> matches = matches(query, null).stream()
            .filter(match -> !match.fallback())
            .toList();
        if (matches.isEmpty()) return query;
        return query + "\n业务场景：" + matches.stream()
            .map(match -> match.name() + " " + match.description())
            .reduce((left, right) -> left + "；" + right)
            .orElse("");
    }

    private List<ScenarioMatch> matches(String text, String metadataType) {
        EnterpriseMetadataTaxonomyService.TaxonomySnapshot taxonomy = taxonomyService.taxonomy();
        String normalized = normalize(text);
        List<ScenarioMatch> matches = new ArrayList<>();
        for (EnterpriseMetadataTaxonomyService.ScenarioDefinition scenario : taxonomy.scenarios()) {
            if (!supportsMetadataType(scenario, metadataType)) continue;
            Set<String> matchedTerms = new LinkedHashSet<>();
            double score = 0.0D;
            for (EnterpriseMetadataTaxonomyService.TermDefinition term : scenario.terms()) {
                if (termMatches(normalized, term)) {
                    matchedTerms.add(term.normalizedTerm());
                    score += Math.max(0.0001D, term.weight());
                }
            }
            if (!matchedTerms.isEmpty()) {
                matches.add(new ScenarioMatch(
                    scenario.code(), scenario.name(), scenario.description(),
                    score, scenario.priority(), false
                ));
            }
        }
        int limit = Math.max(1, properties.getScenarioClassification().getMaxScenariosPerRecord());
        List<ScenarioMatch> selected = matches.stream()
            .sorted(Comparator.comparingDouble(ScenarioMatch::score).reversed()
                .thenComparingInt(ScenarioMatch::priority)
                .thenComparing(ScenarioMatch::code))
            .limit(limit)
            .toList();
        if (!selected.isEmpty()) return selected;
        EnterpriseMetadataTaxonomyService.ScenarioDefinition fallback = taxonomy.fallback();
        return List.of(new ScenarioMatch(
            fallback.code(), fallback.name(), fallback.description(),
            0.0D, fallback.priority(), true
        ));
    }

    private boolean supportsMetadataType(EnterpriseMetadataTaxonomyService.ScenarioDefinition scenario,
                                         String metadataType) {
        return metadataType == null || scenario.metadataTypes().isEmpty()
            || scenario.metadataTypes().stream().anyMatch(metadataType::equalsIgnoreCase);
    }

    private boolean termMatches(String text, EnterpriseMetadataTaxonomyService.TermDefinition term) {
        String candidate = normalize(term.normalizedTerm());
        if (candidate.isBlank()) return false;
        return switch (term.matchType() == null ? "CONTAINS" : term.matchType().toUpperCase(Locale.ROOT)) {
            case "EXACT" -> text.equals(candidate);
            case "PREFIX" -> text.startsWith(candidate);
            case "TOKEN" -> tokenized(text).contains(candidate);
            default -> text.contains(candidate);
        };
    }

    private Set<String> tokenized(String value) {
        Set<String> values = new LinkedHashSet<>();
        for (String token : value.split("[\\s_\\-/.,，。；;:：()（）\\[\\]【】]+")) {
            if (!token.isBlank()) values.add(token);
        }
        return values;
    }

    private String semanticText(EnterpriseMetadataRecord record, List<ScenarioMatch> matches) {
        List<String> parts = new ArrayList<>();
        add(parts, metadataTypeName(record.metadataType()));
        add(parts, record.name());
        add(parts, record.technicalName());
        add(parts, record.description());
        record.attributes().values().forEach(value -> add(parts, value));
        matches.forEach(match -> add(parts, match.name() + " " + match.description()));
        return String.join("\n", parts);
    }

    private String searchableText(EnterpriseMetadataRecord record) {
        List<String> values = new ArrayList<>();
        add(values, record.name());
        add(values, record.technicalName());
        add(values, record.description());
        record.attributes().values().forEach(value -> add(values, value));
        return String.join(" ", values);
    }

    private String metadataTypeName(String metadataType) {
        return switch (metadataType == null ? "" : metadataType) {
            case "metadata_field" -> "标准字段";
            case "metadata_term" -> "标准词根";
            case "metadata_dictionary" -> "标准字典";
            default -> "企业元数据";
        };
    }

    private void add(List<String> target, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.add(String.valueOf(value).trim());
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record ScenarioMatch(String code, String name, String description,
                                 double score, int priority, boolean fallback) {
    }
}
