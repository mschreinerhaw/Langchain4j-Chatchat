package com.chatchat.agents.orchestration.analysis.graph;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NestedRecordReaderTest {
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
