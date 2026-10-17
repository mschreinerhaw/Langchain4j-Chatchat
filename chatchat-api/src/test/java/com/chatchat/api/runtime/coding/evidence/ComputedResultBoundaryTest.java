package com.chatchat.api.runtime.coding.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ComputedResultBoundaryTest {
    final ObjectMapper json = new ObjectMapper();
    final ComputedResultBoundary boundary = new ComputedResultBoundary(json);
    @Test void rejectsUnchangedSourceDocumentInsideNestedResultWrappers() throws Exception {
        String document = "private source paragraph\n".repeat(150);
        boundary.observeInput(json.writeValueAsBytes(Map.of("document", document)));
        assertThat(boundary.copiedSource(List.of(Map.of("analysis", Map.of("text", document))))).isTrue();
    }
    @Test void rejectsSourceCollectionsEvenWhenEachRecordIsSmallOrJsonStringWrapped() throws Exception {
        Map<String,Object> first = Map.of("x", 1, "y", 2), second = Map.of("x", 2, "y", 4);
        boundary.observeInput((json.writeValueAsString(first)+"\n"+json.writeValueAsString(second)).getBytes(StandardCharsets.UTF_8));
        assertThat(boundary.copiedSource(List.of(first, second))).isTrue();
        assertThat(boundary.copiedSource(List.of(Map.of("wrapped", json.writeValueAsString(List.of(first, second)))))).isTrue();
    }
    @Test void acceptsLongOriginalTextAnalysisAndCalculatedMetrics() throws Exception {
        boundary.observeInput(json.writeValueAsBytes(Map.of("document", "source paragraph\n".repeat(200))));
        assertThat(boundary.copiedSource(List.of(Map.of("finding", "Original thematic analysis. ".repeat(300),
            "counts", Map.of("paragraphs",200))))).isFalse();
    }
    @Test void recordOrderingAndWhitespaceDoNotTurnCopiedTextIntoAnalysis() throws Exception {
        String document="source content ".repeat(200);
        boundary.observeInput(json.writeValueAsBytes(Map.of("text",document)));
        assertThat(boundary.copiedSource(List.of(Map.of("finding",document.replace(" ","\n"))))).isTrue();
    }
    @Test void shortSourceQuotationsDoNotInvalidateOtherwiseComputedTextAnalysis() throws Exception {
        String quote="An observed excerpt used as a citation. ".repeat(12);
        boundary.observeInput(json.writeValueAsBytes(Map.of("document",quote)));
        assertThat(boundary.copiedSource(List.of(Map.of("quote",quote,"count",12,"finding","Independent analysis.")))).isFalse();
    }
    @Test void aProsePrefixOrSuffixDoesNotDisguiseAnUnchangedSourceDocument() throws Exception {
        String document="original source paragraph. ".repeat(150);
        boundary.observeInput(json.writeValueAsBytes(Map.of("document",document)));
        assertThat(boundary.copiedSource(List.of(Map.of("analysis","Summary: "+document+" End of report.")))).isTrue();
    }
}
