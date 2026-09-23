package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.document.DocumentSearchCandidate;
import com.chatchat.knowledgebase.search.document.DocumentSearchFilters;
import com.chatchat.knowledgebase.search.security.DocumentVisibilityContext;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DocumentRetrievalWorkflowTest {
    @Test
    void executesInjectedStagesByOrder() {
        List<String> calls = new ArrayList<>();
        DocumentRetrievalWorkflow workflow = new DocumentRetrievalWorkflow(List.of(
            stage("verify-source", 600, calls, false),
            stage("original-query", 100, calls, false),
            stage("hybrid-recall", 300, calls, false),
            stage("fine-rank", 500, calls, false)
        ));

        workflow.execute(plan(), 8);

        assertThat(calls).containsExactly("original-query", "hybrid-recall", "fine-rank", "verify-source");
    }

    @Test
    void stopPreventsLaterStagesFromRunning() {
        List<String> calls = new ArrayList<>();
        DocumentRetrievalWorkflow workflow = new DocumentRetrievalWorkflow(List.of(
            stage("scope", 100, calls, true),
            stage("index", 200, calls, false)
        ));

        workflow.execute(plan(), 8);

        assertThat(calls).containsExactly("scope");
    }

    @Test
    void rejectsAmbiguousStageIdsAtStartup() {
        assertThatThrownBy(() -> new DocumentRetrievalWorkflow(List.of(
            stage("scope", 100, new ArrayList<>(), false),
            stage("scope", 200, new ArrayList<>(), false))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate document retrieval stage");
    }

    @Test
    void candidateRerankersAreComposableAndOrdered() {
        DocumentSearchCandidate first = mock(DocumentSearchCandidate.class);
        DocumentSearchCandidate second = mock(DocumentSearchCandidate.class);
        DocumentRetrievalWorkflowContext context = new DocumentRetrievalWorkflowContext(plan(), 8);
        context.candidates(List.of(first, second));
        DocumentCandidateReranker reverse = new DocumentCandidateReranker() {
            @Override public int order() { return 10; }
            @Override public List<DocumentSearchCandidate> rerank(DocumentSearchPlan ignored,
                                                                   List<DocumentSearchCandidate> candidates) {
                return List.of(candidates.get(1), candidates.get(0));
            }
        };

        new CandidateRerankStage(List.of(reverse)).execute(context);

        assertThat(context.candidates()).containsExactly(second, first);
    }

    @Test
    void queryAndSkillRoleStagesProduceRoutingContext() {
        DocumentSearchPlan scoped = new DocumentSearchPlan("livedata install", 8,
            new DocumentSearchFilters(null, null, null, "ACME", "software", List.of("operations")),
            List.of("doc-1"), List.of("doc-1"), List.of("doc-1"), "doc-1", "how_to",
            List.of("livedata", "install"), false,
            SearchPermissionContext.of("tenant-1", "user-1", List.of("ROLE_OPERATOR")),
            DocumentVisibilityContext.unrestricted(), null);
        DocumentRetrievalWorkflowContext context = new DocumentRetrievalWorkflowContext(scoped, 8);

        new OriginalQueryStage().execute(context);
        new SkillRoleContextStage().execute(context);

        assertThat(context.queryAnalysis().intent()).isEqualTo("how_to");
        assertThat(context.queryAnalysis().entities()).contains("ACME", "software", "livedata");
        assertThat(context.skillRoleContext().documentIds()).containsExactly("doc-1");
        assertThat(context.skillRoleContext().roles()).containsExactly("ROLE_OPERATOR");
    }

    private DocumentRetrievalStage stage(String id, int order, List<String> calls, boolean stop) {
        return new DocumentRetrievalStage() {
            @Override public String id() { return id; }
            @Override public int order() { return order; }
            @Override public void execute(DocumentRetrievalWorkflowContext context) {
                calls.add(id);
                if (stop) context.stop();
            }
        };
    }

    private DocumentSearchPlan plan() {
        return new DocumentSearchPlan("livedata install", 8, null, List.of(), List.of(), List.of(),
            "", "how_to", List.of("livedata", "install"), false,
            SearchPermissionContext.of("tenant-1", "user-1", List.of()),
            DocumentVisibilityContext.unrestricted(), null);
    }
}
