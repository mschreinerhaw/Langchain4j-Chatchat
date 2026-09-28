package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.document.api.search.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.document.application.KnowledgeIrDocumentRecall;
import com.chatchat.knowledgebase.search.security.DocumentVisibilityContext;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthorizedDocumentScopeStageTest {

    @Test
    void stopsWhenKnowledgeIrHasNoAuthorizedProjection() {
        KnowledgeIrDocumentRecall recall = mock(KnowledgeIrDocumentRecall.class);
        SearchProperties properties = new SearchProperties();
        properties.setDocumentFirstEnabled(true);
        DocumentSearchPlan plan = plan(List.of("legacy-livedata-doc"));
        when(recall.recall(plan, 8)).thenReturn(new KnowledgeIrDocumentRecall.Recall(List.of(), "livedata"));
        DocumentRetrievalWorkflowContext context = new DocumentRetrievalWorkflowContext(plan, 8);

        new AuthorizedDocumentScopeStage(recall, properties).execute(context);

        assertThat(context.stopped()).isTrue();
        assertThat(context.authorizedDocumentIds()).isEmpty();
    }

    @Test
    void stillStopsWhenNeitherIrNorDatabaseScopeAuthorizesDocuments() {
        KnowledgeIrDocumentRecall recall = mock(KnowledgeIrDocumentRecall.class);
        SearchProperties properties = new SearchProperties();
        properties.setDocumentFirstEnabled(true);
        DocumentSearchPlan plan = plan(List.of());
        when(recall.recall(plan, 8)).thenReturn(new KnowledgeIrDocumentRecall.Recall(List.of(), "livedata"));
        DocumentRetrievalWorkflowContext context = new DocumentRetrievalWorkflowContext(plan, 8);

        new AuthorizedDocumentScopeStage(recall, properties).execute(context);

        assertThat(context.stopped()).isTrue();
    }

    private DocumentSearchPlan plan(List<String> documentIds) {
        return new DocumentSearchPlan(
            "查找 LiveData 安装说明", 8, null, documentIds, documentIds, documentIds,
            String.join(",", documentIds), "how_to", List.of("livedata", "安装"), false,
            SearchPermissionContext.of("tenant-1", "user-1", List.of("role-1")),
            DocumentVisibilityContext.unrestricted(), null);
    }
}
