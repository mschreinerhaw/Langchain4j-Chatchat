package com.chatchat.agents.runtime.evaluation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class AgentDeterministicScorer {

    private static final List<String> KNOWN_CONNECTORS = List.of(
        "jdbc",
        "filesystem",
        "file system",
        "kafka",
        "hdfs",
        "mysql",
        "spark sql",
        "dataframe",
        "insert into",
        "create table"
    );

    public AgentDeterministicScore score(AgentRegressionCase testCase, AgentRegressionObservation observation) {
        AgentRegressionCase safeCase = testCase == null
            ? new AgentRegressionCase("", "", List.of(), null, null)
            : testCase;
        AgentRegressionObservation safeObservation = observation == null
            ? new AgentRegressionObservation(safeCase.id(), List.of(), 0, null, 0, false, true,
                "missing regression observation", 0.0D, null, "")
            : observation;
        String corpus = String.join("\n", safeObservation.retrievalTexts()).toLowerCase(Locale.ROOT);
        KeywordMatch keywordMatch = match(corpus, safeCase.expected().evidence().mustContainKeywords());
        List<String> expectedEntities = expectedEntities(safeCase);
        KeywordMatch entityMatch = match(corpus, expectedEntities);
        List<String> matchedConnectors = KNOWN_CONNECTORS.stream()
            .filter(connector -> corpus.contains(connector))
            .distinct()
            .toList();

        double keywordCoverage = ratio(keywordMatch.matched().size(), safeCase.expected().evidence().mustContainKeywords().size());
        double entityCoverage = ratio(entityMatch.matched().size(), expectedEntities.size());
        double connectorCoverage = ratio(matchedConnectors.size(), expectedConnectorCount(expectedEntities));
        double chunkCoverage = chunkCoverage(safeObservation.retrievalTexts(), safeCase.expected().evidence().mustContainKeywords());
        double score = average(keywordCoverage, entityCoverage, connectorCoverage, chunkCoverage);

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("deterministicScore", score);
        metrics.put("keywordCoverage", keywordCoverage);
        metrics.put("entityCoverage", entityCoverage);
        metrics.put("connectorCoverage", connectorCoverage);
        metrics.put("chunkCoverage", chunkCoverage);

        List<String> graphEntities = new ArrayList<>();
        graphEntities.addAll(entityMatch.matched());
        for (String connector : matchedConnectors) {
            if (!graphEntities.contains(connector)) {
                graphEntities.add(connector);
            }
        }
        return new AgentDeterministicScore(
            score,
            keywordCoverage,
            entityCoverage,
            connectorCoverage,
            chunkCoverage,
            keywordMatch.matched(),
            keywordMatch.missing(),
            entityMatch.matched(),
            matchedConnectors,
            graph(graphEntities),
            metrics
        );
    }

    private List<String> expectedEntities(AgentRegressionCase testCase) {
        Set<String> entities = new LinkedHashSet<>();
        if (testCase.input() != null && testCase.input().query() != null) {
            addKnownEntities(entities, testCase.input().query());
        }
        addAll(entities, testCase.expected().retrieval().mustContain());
        addAll(entities, testCase.expected().evidence().mustContainKeywords());
        addAll(entities, testCase.expected().answer().mustContainAny());
        return entities.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
    }

    private void addKnownEntities(Set<String> entities, String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String connector : KNOWN_CONNECTORS) {
            if (normalized.contains(connector)) {
                entities.add(connector);
            }
        }
    }

    private void addAll(Set<String> values, List<String> candidates) {
        for (String candidate : candidates == null ? List.<String>of() : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                values.add(candidate);
            }
        }
    }

    private KeywordMatch match(String text, List<String> keywords) {
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords == null ? List.<String>of() : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            String candidate = keyword.toLowerCase(Locale.ROOT);
            if (normalized.contains(candidate)) {
                matched.add(keyword);
            } else {
                missing.add(keyword);
            }
        }
        return new KeywordMatch(matched, missing);
    }

    private int expectedConnectorCount(List<String> expectedEntities) {
        long expected = expectedEntities == null ? 0L : expectedEntities.stream()
            .filter(entity -> KNOWN_CONNECTORS.contains(entity.toLowerCase(Locale.ROOT)))
            .count();
        return (int) Math.max(1L, expected);
    }

    private double chunkCoverage(List<String> chunks, List<String> expectedKeywords) {
        if (chunks == null || chunks.isEmpty()) {
            return 0.0D;
        }
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return 1.0D;
        }
        int covered = 0;
        for (String chunk : chunks) {
            String normalized = chunk == null ? "" : chunk.toLowerCase(Locale.ROOT);
            boolean matched = expectedKeywords.stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
            if (matched) {
                covered++;
            }
        }
        return ratio(covered, chunks.size());
    }

    private AgentRegressionResult.EvidenceGraph graph(List<String> entities) {
        List<String> safeEntities = entities == null ? List.of() : List.copyOf(entities);
        List<String> relations = new ArrayList<>();
        if (safeEntities.contains("spark sql") && safeEntities.contains("jdbc")) {
            relations.add("Spark SQL -> uses -> JDBC");
        }
        if (safeEntities.contains("spark sql") && safeEntities.contains("filesystem")) {
            relations.add("Spark SQL -> reads -> FileSystem");
        }
        if (relations.isEmpty() && safeEntities.size() > 1) {
            String root = safeEntities.get(0);
            for (int i = 1; i < safeEntities.size(); i++) {
                relations.add(root + " -> related_to -> " + safeEntities.get(i));
            }
        }
        return new AgentRegressionResult.EvidenceGraph(safeEntities, relations);
    }

    private double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 1.0D : ((double) numerator) / denominator;
    }

    private double average(double... values) {
        if (values == null || values.length == 0) {
            return 0.0D;
        }
        double total = 0.0D;
        for (double value : values) {
            total += Math.max(0.0D, Math.min(1.0D, value));
        }
        return total / values.length;
    }

    private record KeywordMatch(List<String> matched, List<String> missing) {
    }
}
