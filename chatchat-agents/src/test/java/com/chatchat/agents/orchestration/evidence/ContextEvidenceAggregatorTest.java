package com.chatchat.agents.orchestration.evidence;

import com.chatchat.agents.orchestration.analysis.ContextTokenEstimator;
import com.chatchat.agents.orchestration.evidence.ContextEvidenceAggregator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextEvidenceAggregatorTest {

    private record BatchEnvelope(String status, List<RowEvidence> results) {
    }

    private record RowEvidence(String callId, Map<String, Object> output) {
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesJavaRecordsStructurallyInsteadOfUsingToString() {
        BatchEnvelope batch = new BatchEnvelope("SUCCESS", List.of(
            new RowEvidence("asset", Map.of(
                "records", List.of(Map.of("assetValue", 125000.50, "profit", 3200.25))))
        ));

        Map<String, Object> aggregated =
            (Map<String, Object>) new ContextEvidenceAggregator().aggregate(batch);

        assertThat(aggregated).containsEntry("status", "SUCCESS");
        assertThat(aggregated.toString())
            .contains("assetValue", "125000.5", "profit", "3200.25")
            .doesNotContain("BatchEnvelope[", "RowEvidence[");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesRowsIntoDistributionsAndNumericStatisticsWithoutMutatingRawRows() {
        List<Map<String, Object>> rawRows = List.of(
            Map.of("event", "db file async I/O submit", "wait_time", 271713, "count", 300000),
            Map.of("event", "db file async I/O submit", "wait_time", 10000, "count", 100),
            Map.of("event", "log file parallel write", "wait_time", 5000, "count", 20)
        );
        Map<String, Object> raw = Map.of("wait_events", rawRows);

        Map<String, Object> aggregated =
            (Map<String, Object>) new ContextEvidenceAggregator().aggregate(raw);
        Map<String, Object> collection =
            (Map<String, Object>) aggregated.get("wait_events");
        Map<String, Object> rowShape =
            (Map<String, Object>) collection.get("rowShape");
        Map<String, Object> fieldProfiles =
            (Map<String, Object>) rowShape.get("fieldProfiles");
        Map<String, Object> waitTime =
            (Map<String, Object>) fieldProfiles.get("wait_time");
        Map<String, Object> events =
            (Map<String, Object>) fieldProfiles.get("event");

        assertThat(collection).containsEntry("count", 3);
        assertThat(aggregated)
            .containsEntry("_assessmentCapability", "LIMITED_UNKNOWN_CONTRACT")
            .containsKey("_lossNotice");
        assertThat(collection.get("representativeRows").toString())
            .contains("db file async I/O submit", "wait_time");
        assertThat(waitTime)
            .containsEntry("min", 5000.0)
            .containsEntry("max", 271713.0)
            .containsKey("average");
        List<Map<String, Object>> topValues =
            (List<Map<String, Object>>) events.get("topValues");
        assertThat(topValues.get(0))
            .containsEntry("value", "db file async I/O submit")
            .containsEntry("count", 2L);
        assertThat(rawRows).hasSize(3);
        assertThat(rawRows.get(0)).containsEntry("wait_time", 271713);
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesShortTextAndBoundsLongTextWithSemanticEdges() {
        ContextEvidenceAggregator aggregator = new ContextEvidenceAggregator();
        Map<String, Object> aggregated = (Map<String, Object>) aggregator.aggregate(Map.of(
            "short", "business status is active",
            "long", "A".repeat(1200) + "TAIL_MARKER"
        ));

        assertThat(aggregated.get("short")).isEqualTo("business status is active");
        Map<String, Object> longText = (Map<String, Object>) aggregated.get("long");
        assertThat(longText).containsEntry("truncated", true);
        assertThat(longText.get("tail").toString()).endsWith("TAIL_MARKER");
    }

    @Test
    void estimatesChineseEnglishAndJsonStructureAsTokensRatherThanCharacterRatioOnly() {
        ContextTokenEstimator estimator = new ContextTokenEstimator();

        ContextTokenEstimator.Size chinese = estimator.estimate("数据库等待事件出现严重异常需要立即检查");
        ContextTokenEstimator.Size english = estimator.estimate("database wait event anomaly");
        ContextTokenEstimator.Size json = estimator.estimate(Map.of(
            "event", "db file async I/O submit",
            "wait_time", 271713
        ));

        assertThat(chinese.tokens()).isGreaterThan(english.tokens());
        assertThat(json.tokens()).isGreaterThan(english.tokens());
        assertThat(json.chars()).isGreaterThan(0);
    }
}
