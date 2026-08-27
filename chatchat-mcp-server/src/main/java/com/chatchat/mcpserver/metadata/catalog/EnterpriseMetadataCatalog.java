package com.chatchat.mcpserver.metadata.catalog;

import com.chatchat.mcpserver.metadata.ingestion.EnterpriseMetadataDatabaseLoader;
import com.chatchat.mcpserver.metadata.config.EnterpriseMetadataProperties;
import com.chatchat.mcpserver.metadata.search.EnterpriseMetadataScenarioClassifier;
import com.chatchat.mcpserver.metadata.taxonomy.EnterpriseMetadataTaxonomyService;
import com.chatchat.mcpserver.metadata.search.EnterpriseMetadataVectorizer;
import com.chatchat.mcpserver.metadata.ingestion.EnterpriseMetadataWorkbookLoader;

import com.chatchat.mcpserver.search.OpenSearchMcpSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EnterpriseMetadataCatalog {

    private final EnterpriseMetadataProperties properties;
    private final EnterpriseMetadataDatabaseLoader databaseLoader;
    private final EnterpriseMetadataWorkbookLoader legacyWorkbookLoader;
    private final EnterpriseMetadataScenarioClassifier scenarioClassifier;
    private final EnterpriseMetadataVectorizer vectorizer;
    private final OpenSearchMcpSearchService openSearch;
    private final EnterpriseMetadataTaxonomyService taxonomyService;
    private final AtomicReference<Snapshot> snapshot =
        new AtomicReference<>(new Snapshot(List.of(), null, Map.of()));

    public EnterpriseMetadataCatalog(EnterpriseMetadataProperties properties,
                                     EnterpriseMetadataWorkbookLoader loader,
                                     EnterpriseMetadataScenarioClassifier scenarioClassifier,
                                     EnterpriseMetadataVectorizer vectorizer,
                                     OpenSearchMcpSearchService openSearch) {
        this.properties = properties;
        this.databaseLoader = null;
        this.legacyWorkbookLoader = loader;
        this.scenarioClassifier = scenarioClassifier;
        this.vectorizer = vectorizer;
        this.openSearch = openSearch;
        this.taxonomyService = null;
    }

    @Autowired
    public EnterpriseMetadataCatalog(EnterpriseMetadataProperties properties,
                                     EnterpriseMetadataDatabaseLoader databaseLoader,
                                     EnterpriseMetadataScenarioClassifier scenarioClassifier,
                                     EnterpriseMetadataVectorizer vectorizer,
                                     OpenSearchMcpSearchService openSearch,
                                     EnterpriseMetadataTaxonomyService taxonomyService) {
        this.properties = properties;
        this.databaseLoader = databaseLoader;
        this.legacyWorkbookLoader = null;
        this.scenarioClassifier = scenarioClassifier;
        this.vectorizer = vectorizer;
        this.openSearch = openSearch;
        this.taxonomyService = taxonomyService;
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (properties.isEnabled() && properties.isRefreshOnStartup()) {
            refresh();
        }
    }

    public synchronized Map<String, Object> refresh() {
        if (!properties.isEnabled()) {
            snapshot.set(new Snapshot(List.of(), Instant.now(), Map.of()));
            return status();
        }
        List<EnterpriseMetadataRecord> sourceRecords = databaseLoader == null
            ? properties.resolvedSourceLocationPatterns().stream()
                .flatMap(pattern -> legacyWorkbookLoader.load(pattern).stream())
                .toList()
            : databaseLoader.load();
        List<EnterpriseMetadataRecord> records = sourceRecords.stream()
            .map(scenarioClassifier::classify)
            .collect(Collectors.collectingAndThen(
                Collectors.toMap(
                    record -> record.metadataType() + ":" + record.id(),
                    Function.identity(),
                    (first, ignored) -> first,
                    LinkedHashMap::new
                ),
                values -> List.copyOf(values.values())
            ));
        Map<String, Long> counts = records.stream().collect(
            java.util.stream.Collectors.groupingBy(
                EnterpriseMetadataRecord::metadataType,
                LinkedHashMap::new,
                java.util.stream.Collectors.counting()
            ));
        Snapshot next = new Snapshot(records, Instant.now(), Map.copyOf(counts));
        snapshot.set(next);
        indexInOpenSearch(records);
        log.info("Enterprise metadata catalog refreshed records={} counts={}", records.size(), counts);
        return status();
    }

    public List<EnterpriseMetadataRecord> records() {
        return snapshot.get().records();
    }

    public Map<String, Object> status() {
        Snapshot current = snapshot.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("source", databaseLoader == null ? "workbook-test-adapter" : "database");
        result.put("importSourceLocationPatterns", properties.resolvedSourceLocationPatterns());
        if (databaseLoader != null) {
            result.put("databaseCounts", databaseLoader.counts());
        }
        result.put("indexName", properties.getIndexName());
        result.put("recordCount", current.records().size());
        result.put("counts", current.counts());
        result.put("refreshedAt", current.refreshedAt());
        result.put("openSearchActive", openSearch != null && openSearch.enabled());
        result.put("knnEnabled", properties.getKnn().isEnabled());
        result.put("knnVectorField", properties.getKnn().getVectorField());
        result.put("knnDimension", properties.getKnn().getDimension());
        result.put("scenarioClassificationEnabled", properties.getScenarioClassification().isEnabled());
        result.put("scenarioProvider", properties.getScenarioClassification().getProvider());
        result.put("scenarioCacheTtlSeconds", properties.getScenarioClassification().getCacheTtlSeconds());
        if (taxonomyService != null) {
            EnterpriseMetadataTaxonomyService.TaxonomySnapshot taxonomy = taxonomyService.taxonomy();
            List<Map<String, Object>> scenarios = new java.util.ArrayList<>(taxonomy.scenarios().stream()
                .map(this::scenarioStatus)
                .toList());
            scenarios.add(scenarioStatus(taxonomy.fallback()));
            result.put("scenarios", List.copyOf(scenarios));
        } else {
            result.put("scenarios", List.of());
        }
        return result;
    }

    private Map<String, Object> scenarioStatus(
        EnterpriseMetadataTaxonomyService.ScenarioDefinition scenario) {
        return Map.of(
            "code", scenario.code(),
            "name", scenario.name(),
            "description", scenario.description(),
            "priority", scenario.priority(),
            "fallback", scenario.fallback(),
            "termCount", scenario.terms().size()
        );
    }

    private void indexInOpenSearch(List<EnterpriseMetadataRecord> records) {
        if (openSearch == null || !openSearch.enabled()) {
            return;
        }
        try {
            List<Map<String, Object>> documents = records.stream().map(record -> {
                Map<String, Object> document = new LinkedHashMap<>(record.toMap());
                if (properties.getKnn().isEnabled()) {
                    List<Float> vector = vectorizer.vectorize(
                        String.valueOf(document.getOrDefault("semanticText", record.name()))
                    );
                    if (!vector.isEmpty()) {
                        document.put(properties.getKnn().getVectorField(), vector);
                    }
                }
                return Map.copyOf(document);
            }).toList();
            openSearch.replaceEnterpriseMetadata(
                properties.getIndexName(),
                documents,
                properties.getKnn().isEnabled(),
                properties.getKnn().getVectorField(),
                properties.getKnn().getDimension()
            );
        } catch (Exception ex) {
            log.warn("Enterprise metadata OpenSearch refresh failed; in-memory catalog remains available: {}",
                ex.getMessage());
        }
    }

    private record Snapshot(List<EnterpriseMetadataRecord> records,
                            Instant refreshedAt,
                            Map<String, Long> counts) {
        private Snapshot {
            records = records == null ? List.of() : List.copyOf(records);
            counts = counts == null ? Map.of() : Map.copyOf(counts);
        }
    }
}
