package com.chatchat.mcpserver.search.engine;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class MetadataSearchTextTest {
    @Test
    void wideSchemasKeepEveryFieldSearchableWithoutSerializingDiagnosticJson() {
        var columns = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 500; i++) columns.add(Map.of("name", "field_" + i,
            "comment", "客户服务查询字段".repeat(20), "dataType", "string", "datasourceId", "private-id"));
        var evidence = Map.<String, Object>of("columns", columns);
        var text = OpenSearchMcpSearchService.metadataSearchText("warehouse schema table", evidence);
        assertThat(text).contains("warehouse schema table", "field_0", "field_499", "string");
        assertThat(String.join(" ", text)).doesNotContain("private-id", "datasourceId", "ordinalPosition");
        assertThat(columns).hasSize(500); // Full evidence is not shortened to fit the analyzer.
        assertThat(text).allSatisfy(value -> assertThat(value.codePointCount(0, value.length())).isLessThanOrEqualTo(1024));
    }

    @Test
    void unusuallyLongCommentsAreSplitWithoutDroppingTextOrBreakingUnicode() {
        String comment = "数据𠀀".repeat(5000);
        var text = OpenSearchMcpSearchService.metadataSearchText("", Map.of("columns", List.of(Map.of("comment", comment))));
        assertThat(String.join("", text)).isEqualTo(comment);
        assertThat(text).allSatisfy(value -> {
            assertThat(value.codePointCount(0, value.length())).isLessThanOrEqualTo(1024);
            assertThat(Character.isHighSurrogate(value.charAt(value.length()-1))).isFalse();
        });
    }
}
