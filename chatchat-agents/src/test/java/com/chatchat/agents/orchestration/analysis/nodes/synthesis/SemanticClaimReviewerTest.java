package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class SemanticClaimReviewerTest {
    @Test void acceptsEvidenceBoundRepairAndRejectsInventedEvidence() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {"schemaVersion":"semantic_claim_review.v1","reviews":[{"claimId":"F1",
             "decision":"REPAIR","issue":"scope overflow","evidenceIds":["e1"],
             "repairAction":"NARROW_SCOPE","repairedClaim":"observed sample only"}]}
            """);
        var reviewer = new SemanticClaimReviewer(model, 1000);
        assertThat(reviewer.review(Map.of(), Set.of("F1"), Set.of("e1")).status()).isEqualTo("REVIEWED");
        assertThat(reviewer.review(Map.of(), Set.of("F1"), Set.of("other")).status()).isEqualTo("INVALID_RESPONSE");
    }
    @Test void timeoutAndProviderFailureAreBoundedDispositions() {
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenAnswer(invocation -> {
            new java.util.concurrent.CountDownLatch(1).await(); return "";
        });
        assertThat(new SemanticClaimReviewer(model, 30).review(Map.of(), Set.of("F1"), Set.of("e1")).status())
            .isEqualTo("TIMEOUT");
        var failed = mock(ChatModel.class);
        when(failed.chat(anyString())).thenThrow(new IllegalStateException("unavailable"));
        assertThat(new SemanticClaimReviewer(failed, 1000).review(Map.of(), Set.of("F1"), Set.of("e1")).status())
            .isEqualTo("UNAVAILABLE");
    }
    @Test void oversizedEvidenceNeverStartsModelCall() {
        var model = mock(ChatModel.class);
        assertThat(new SemanticClaimReviewer(model, 1000).review(Map.of("data", "x".repeat(48001)), Set.of(), Set.of()).status())
            .isEqualTo("BUDGET_EXHAUSTED");
        verifyNoInteractions(model);
    }
    @Test void runtimeCancellationStopsWaitingForTheProvider() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenAnswer(invocation -> {
            cancelled.set(true);
            new java.util.concurrent.CountDownLatch(1).await();
            return "";
        });
        assertThatThrownBy(() -> new SemanticClaimReviewer(model, 1000, cancelled::get)
            .review(Map.of(), Set.of("F1"), Set.of("e1")))
            .isInstanceOf(java.util.concurrent.CancellationException.class);
    }
}
