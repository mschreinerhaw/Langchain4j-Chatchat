package com.chatchat.agents.runtime;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerCandidateCollectorTest {

    @Test
    void acceptsCandidatesFromAnyRuntimeStageAndDrainsThemOnce() {
        AnswerCandidateCollector collector = new AnswerCandidateCollector();
        Map<String, Object> metadata = new LinkedHashMap<>();

        collector.register(
            metadata,
            "document_grounding",
            "Document-grounded answer",
            List.of("doc://policy#chunk=1"),
            Map.of("component", "document_runtime")
        );
        collector.register(metadata, "http_result_review", "HTTP analysis answer");

        List<AnswerCandidateCollector.Candidate> candidates = collector.drain(metadata);

        assertThat(candidates)
            .extracting(AnswerCandidateCollector.Candidate::stage)
            .containsExactly("document_grounding", "http_result_review");
        assertThat(candidates.get(0).evidenceRefs()).containsExactly("doc://policy#chunk=1");
        assertThat(collector.hasCandidates(metadata)).isFalse();
        assertThat(collector.drain(metadata)).isEmpty();
    }

    @Test
    void deduplicatesEquivalentAnswersAcrossDomainAdapters() {
        AnswerCandidateCollector collector = new AnswerCandidateCollector();
        Map<String, Object> metadata = new LinkedHashMap<>();

        collector.register(metadata, "sql", "Same\nanswer");
        collector.register(metadata, "document", "Same answer");

        assertThat(collector.drain(metadata)).hasSize(1);
    }
}
