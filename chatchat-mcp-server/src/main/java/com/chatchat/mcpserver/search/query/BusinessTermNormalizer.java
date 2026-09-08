package com.chatchat.mcpserver.search.query;

import com.chatchat.mcpserver.search.engine.LuceneSearchProperties;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration-driven, bidirectional terminology expansion shared by query and index projection.
 * The implementation intentionally contains no business terms.
 */
@Component
public class BusinessTermNormalizer {

    private final boolean enabled;
    private final int maxExpansionsPerQuery;
    private final List<List<String>> groups;

    public BusinessTermNormalizer(LuceneSearchProperties properties) {
        LuceneSearchProperties.BusinessTerms config = properties == null
            ? null : properties.getBusinessTerms();
        this.enabled = config != null && config.isEnabled();
        this.maxExpansionsPerQuery = config == null ? 16 : Math.max(1, config.getMaxExpansionsPerQuery());
        this.groups = compile(config == null ? Map.of() : config.getSynonymGroups());
    }

    public List<String> expandQueries(Collection<String> queries) {
        Set<String> result = new LinkedHashSet<>();
        if (queries != null) {
            queries.forEach(query -> expansions(query).stream()
                .filter(expansion -> !expansion.equals(SearchQueryTokenizer.normalize(query)))
                .forEach(result::add));
        }
        return List.copyOf(result);
    }

    public List<String> expansions(String value) {
        String input = SearchQueryTokenizer.normalize(value);
        if (input.isBlank() || !enabled || groups.isEmpty()) return input.isBlank() ? List.of() : List.of(input);
        Set<String> result = new LinkedHashSet<>();
        result.add(input);
        for (List<String> group : groups) {
            for (String matched : group) {
                if (!input.contains(matched)) continue;
                for (String equivalent : group) {
                    if (equivalent.equals(matched)) continue;
                    result.add(equivalent);
                    result.add(input.replace(matched, equivalent));
                    if (result.size() >= maxExpansionsPerQuery + 1) return List.copyOf(result);
                }
            }
        }
        return List.copyOf(result);
    }

    /** Appends configured equivalents to searchable profile text while retaining the source text. */
    public String enrichIndexText(String value) {
        String source = value == null ? "" : value.trim();
        if (source.isBlank()) return source;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(source);
        values.addAll(expansions(source));
        return String.join(" ", values);
    }

    public Map<String, Object> metadata() {
        return Map.of(
            "enabled", enabled,
            "source", "operator_configuration",
            "embeddedBusinessTerms", false,
            "synonymGroupCount", groups.size(),
            "expansionPolicy", "bidirectional_phrase_and_equivalent_units"
        );
    }

    private List<List<String>> compile(Map<String, List<String>> configured) {
        if (configured == null || configured.isEmpty()) return List.of();
        Map<String, List<String>> unique = new LinkedHashMap<>();
        configured.forEach((canonical, aliases) -> {
            LinkedHashSet<String> group = new LinkedHashSet<>();
            add(group, canonical);
            if (aliases != null) aliases.forEach(alias -> add(group, alias));
            if (group.size() > 1) unique.put(String.join("\u0000", group), List.copyOf(group));
        });
        return List.copyOf(unique.values());
    }

    private void add(Set<String> values, String value) {
        String normalized = SearchQueryTokenizer.normalize(value);
        if (!normalized.isBlank()) values.add(normalized);
    }
}
