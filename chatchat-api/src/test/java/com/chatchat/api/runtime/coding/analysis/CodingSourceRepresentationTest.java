package com.chatchat.api.runtime.coding.analysis;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CodingSourceRepresentationTest {
    @Test void combinedHintsWhitelistEachDeclaredSourceAndDoNotGuessUnknownSources() {
        var hint = CodingSourceRepresentation.describe(Map.of("inputVersions", java.util.List.of(
            Map.of("datasetReference","opaque#payload","sourceRepresentation",Map.of(
                "contentType","application/json","recordMeaning","CONTENT_CHUNK_NOT_BUSINESS_ROW",
                "content","private-source","reconstruction","private-instruction")),
            Map.of("datasetReference","opaque#unknown","sourceRepresentation",Map.of("contentType","unknown")))));
        assertThat(hint.toString()).contains("opaque#payload","application/json","exact datasetReference")
            .doesNotContain("private-source","private-instruction","opaque#unknown");
    }
    @Test void declaredJsonChunksCarryDecodingSemanticsWithoutSourceValues() {
        var hint = CodingSourceRepresentation.describe(Map.of("contentType", "application/json",
            "recordMeaning", "CONTENT_CHUNK_NOT_BUSINESS_ROW", "customerValue", "private-value",
            "content", "private-source", "rawData", Map.of("secret", "private-raw")));
        assertThat(hint).containsEntry("contentType", "application/json").containsEntry("contentField", "content");
        assertThat(hint.toString()).contains("Decode the reconstructed text as JSON first")
            .doesNotContain("private-value", "private-source", "private-raw", "customerValue");
    }
    @Test void plainCommandStreamsDoNotAcquireJsonSemantics() {
        var hint = CodingSourceRepresentation.describe(Map.of("contentType", "text/plain", "stream", "stdout"));
        assertThat(hint).containsEntry("contentType", "text/plain");
        assertThat(hint.toString()).doesNotContain("Decode the reconstructed text as JSON");
    }
    @Test void ordinaryBusinessTablesDoNotBecomeTextChunksFromMimeAlone() {
        assertThat(CodingSourceRepresentation.describe(Map.of("contentType", "application/json"))).isEmpty();
        assertThat(CodingSourceRepresentation.describe(Map.of())).isEmpty();
    }
    @Test void unsupportedEncodingsAreNotGuessed() {
        assertThat(CodingSourceRepresentation.describe(Map.of("contentType", "invented/format",
            "recordMeaning", "CONTENT_CHUNK_NOT_BUSINESS_ROW"))).isEmpty();
    }
}
