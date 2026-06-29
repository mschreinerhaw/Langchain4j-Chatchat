package com.chatchat.mcpserver.sql;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class MetadataResolverEngine {

    private static final double EXACT_TABLE_MATCH_WEIGHT = 0.5;
    private static final double SCHEMA_HINT_MATCH_WEIGHT = 0.2;
    private static final double DATASOURCE_AFFINITY_WEIGHT = 0.2;
    private static final double ROUTING_HISTORY_WEIGHT = 0.1;
    private static final double DETERMINISTIC_SCORE_GAP = 0.15;

    private final DatasourceBindingService datasourceBindingService;
    private final TableSemanticMatcher semanticMatcher;
    private final RoutingFusionEngine routingFusionEngine;

    public List<TableLocation> rank(MetadataResolveContext context, List<TableLocation> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
            .map(candidate -> new TableLocation(
                candidate.datasourceId(),
                candidate.database(),
                candidate.schema(),
                candidate.table(),
                candidate.tableType(),
                candidate.tableRows(),
                fusedScore(context, candidate)
            ))
            .sorted(Comparator.comparingDouble(TableLocation::score).reversed()
                .thenComparing(location -> String.valueOf(location.datasourceId()))
                .thenComparing(location -> String.valueOf(location.database()))
                .thenComparing(location -> String.valueOf(location.table())))
            .toList();
    }

    public TableLocation select(MetadataResolveContext context, List<TableLocation> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return null;
        }
        if (ranked.size() == 1) {
            return ranked.get(0);
        }
        String preferred = normalizeIdentifier(context == null ? null : context.preferredSchema());
        if (preferred != null) {
            List<TableLocation> preferredMatches = ranked.stream()
                .filter(candidate -> preferred.equals(normalizeIdentifier(candidate.database()))
                    || preferred.equals(normalizeIdentifier(candidate.schema())))
                .toList();
            if (preferredMatches.size() == 1) {
                return preferredMatches.get(0);
            }
        }
        TableLocation first = ranked.get(0);
        TableLocation second = ranked.get(1);
        return first.score() - second.score() > DETERMINISTIC_SCORE_GAP ? first : null;
    }

    public double confidence(List<TableLocation> ranked, TableLocation selected) {
        if (selected == null) {
            return 0.0;
        }
        if (ranked != null && ranked.size() == 1) {
            return Math.max(0.95, selected.score());
        }
        return selected.score();
    }

    private double score(MetadataResolveContext context, TableLocation candidate) {
        double value = 0.0;
        value += semanticMatcher.similarity(context == null ? null : context.tableName(), candidate.table())
            * EXACT_TABLE_MATCH_WEIGHT;
        String preferred = normalizeIdentifier(context == null ? null : context.preferredSchema());
        if (preferred != null
            && (preferred.equals(normalizeIdentifier(candidate.database()))
            || preferred.equals(normalizeIdentifier(candidate.schema())))) {
            value += SCHEMA_HINT_MATCH_WEIGHT;
        }
        value += datasourceBindingService.datasourceAffinity(
            context == null ? null : context.datasource(),
            candidate
        ) * DATASOURCE_AFFINITY_WEIGHT;
        if (candidate.tableType() == null || "BASE TABLE".equalsIgnoreCase(candidate.tableType())) {
            value += ROUTING_HISTORY_WEIGHT;
        }
        return Math.min(1.0, value);
    }

    private double fusedScore(MetadataResolveContext context, TableLocation candidate) {
        double metadataScore = score(context, candidate);
        double routingScore = datasourceBindingService.datasourceAffinity(
            context == null ? null : context.datasource(),
            candidate
        );
        return routingFusionEngine.finalScore(routingScore, metadataScore, candidate);
    }

    private boolean equalsNormalized(String left, String right) {
        String normalizedLeft = normalizeIdentifier(left);
        String normalizedRight = normalizeIdentifier(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private String normalizeIdentifier(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
