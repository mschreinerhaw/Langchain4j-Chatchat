package com.chatchat.agents.orchestration.analysis.graph;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NestedRecordReaderTest {
    @Test void absentOriginalFieldsAreDistinguishedFromPreviewTruncation() {
        var source = Map.<String, Object>of("references", List.of(Map.of("id", "reference-only")));
        var page = NestedRecordReader.readRecord(source, 1,
            Map.of("path", List.of("references"), "fields", List.of("id", "description")));
        assertThat(page).containsEntry("truncated", false).containsEntry("hasMore", false);
        assertThat((Map<String, Object>) page.get("fieldAvailability"))
            .containsEntry("absentInReturnedItems", List.of("description")).containsEntry("availableFields", List.of("id"));
        assertThat(page.get("items")).isEqualTo(List.of(Map.of("id", "reference-only")));
    }
    @Test void oversizedObjectsRetainSmallFieldsAndKeepOriginalLeafReadable() {
        var source = Map.<String, Object>of("rows", List.of(Map.of("identifier", "exact-id", "large", "x".repeat(12000))));
        var page = NestedRecordReader.readRecord(source, 1, Map.of("path", List.of("rows")));
        var item = (Map<?, ?>) ((List<?>) page.get("items")).get(0);
        assertThat(item.get("value")).isEqualTo(Map.of("identifier", "exact-id"));
        assertThat(item.get("partial")).isEqualTo(true);
        assertThat(NestedRecordReader.readValue(source, 1, Map.of("path", List.of("rows", 0, "large"))))
            .containsEntry("totalChars", 12000).containsEntry("nextChar", 3000);
    }
    @Test void boundedPagesExposeActualContinuationAndProjectionKeepsExactValues() {
        var rows = java.util.stream.IntStream.range(0, 30)
            .mapToObj(i -> Map.of("id", i, "details", "x".repeat(1700))).toList();
        var source = Map.<String, Object>of("rows", rows);
        var first = NestedRecordReader.readRecord(source, 1, Map.of("path", List.of("rows"), "limit", 30));
        assertThat((int) first.get("nextItem")).isBetween(1, 29);
        assertThat(first).containsEntry("hasMore", true).containsEntry("truncated", true);
        var next = NestedRecordReader.readRecord(source, 1,
            Map.of("path", List.of("rows"), "fromItem", first.get("nextItem"), "fields", List.of("id"), "limit", 30));
        assertThat(next).containsEntry("nextItem", 30).containsEntry("hasMore", false);
        assertThat((List<?>) next.get("items")).hasSize(30 - (int) first.get("nextItem"));
        assertThat(com.chatchat.agents.protocol.ModelProtocolJson.compact(first).length()).isLessThanOrEqualTo(4000);
    }
    @Test void individualObjectsAndTextRemainReadableWhenTheirPreviewIsTooLarge() {
        var source = Map.<String, Object>of("object", Map.of("id", "exact", "text", "a".repeat(7000)));
        var navigation = NestedRecordReader.readValue(source, 1, Map.of("path", List.of("object")));
        assertThat(navigation).containsEntry("valueOmitted", true);
        assertThat(NestedRecordReader.readValue(source, 1, Map.of("path", List.of("object", "id"))))
            .containsEntry("value", "exact").containsEntry("complete", true);
        assertThat(NestedRecordReader.readValue(source, 1,
            Map.of("path", List.of("object", "text"), "fromChar", 3000)))
            .containsEntry("value", "a".repeat(3000)).containsEntry("nextChar", 6000).containsEntry("hasMore", true);
        assertThatThrownBy(() -> NestedRecordReader.readValue(source, 1, Map.of("path", List.of("missing"))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void searchHitsAndEmbeddedBusinessRowsHaveSeparateCoordinates() {
        List<Map<String, Object>> hits = new ArrayList<>();
        for (int i = 0; i < 6; i++) hits.add(Map.of("title", "news"));
        hits.add(Map.of("data", Map.of("rows", java.util.stream.IntStream.range(0, 20)
            .mapToObj(i -> Map.of("value", i)).toList())));
        assertThat(NestedRecordReader.catalog(hits)).contains(Map.of("record", 7,
            "path", List.of("data", "rows"), "itemCount", 20, "indexBase", 0));
        var page = NestedRecordReader.read(hits, Map.of("record", 7, "path", List.of("data", "rows"), "fromItem", 15, "limit", 100));
        assertThat(page).containsEntry("availableItemCount", 20).containsEntry("toItemExclusive", 20);
        assertThat((List<?>) page.get("items")).hasSize(5);
        assertThatThrownBy(() -> NestedRecordReader.read(hits, Map.of("record", 21, "path", List.of("data", "rows"))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
