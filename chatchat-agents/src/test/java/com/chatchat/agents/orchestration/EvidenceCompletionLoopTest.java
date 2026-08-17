package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCompletionLoopTest {

    @Test
    void completesAcrossDocumentDatabaseAndWebAndReassessesEveryRound() {
        EvidenceCompletionLoop loop = new EvidenceCompletionLoop();
        List<EvidenceCompletionLoop.SourceContract> sources = List.of(
            new EvidenceCompletionLoop.SourceContract("docs", "document", Map.of()),
            new EvidenceCompletionLoop.SourceContract("db", "database", Map.of()),
            new EvidenceCompletionLoop.SourceContract("web", "web", Map.of()));

        EvidenceCompletionLoop.Result result = loop.run(
            new EvidenceCompletionLoop.Request("customer question", 3, sources, List.of()),
            request -> List.of(new EvidenceCompletionLoop.EvidenceItem(
                request.source().id() + "-" + request.round(), request.source().id(),
                request.source().kind(), "evidence", "section-1", Map.of())),
            (query, evidence) -> {
                List<String> missing = sources.stream().map(EvidenceCompletionLoop.SourceContract::id)
                    .filter(id -> evidence.stream().noneMatch(item -> id.equals(item.sourceId()))).toList();
                return new EvidenceCompletionLoop.Assessment(missing.isEmpty(), evidence.size() / 3.0D,
                    missing, missing.isEmpty() ? List.of() : List.of("source coverage incomplete"));
            });

        assertThat(result.assessment().sufficient()).isTrue();
        assertThat(result.evidence()).extracting(EvidenceCompletionLoop.EvidenceItem::sourceKind)
            .containsExactlyInAnyOrder("document", "database", "web");
        assertThat(result.rounds()).hasSize(1);
        assertThat(result.stopReason()).isEqualTo("evidence_sufficient");
    }

    @Test
    void stopsAtConfiguredBoundWhenEvidenceRemainsInsufficient() {
        EvidenceCompletionLoop.Result result = new EvidenceCompletionLoop().run(
            new EvidenceCompletionLoop.Request("q", 2,
                List.of(new EvidenceCompletionLoop.SourceContract("docs", "document", Map.of())), List.of()),
            request -> List.of(new EvidenceCompletionLoop.EvidenceItem(
                "e" + request.round(), "docs", "document", "partial", "", Map.of())),
            (query, evidence) -> new EvidenceCompletionLoop.Assessment(false, 0.2D, List.of("docs"), List.of("missing fact")));

        assertThat(result.rounds()).hasSize(2);
        assertThat(result.stopReason()).isEqualTo("max_rounds_reached");
    }
}
