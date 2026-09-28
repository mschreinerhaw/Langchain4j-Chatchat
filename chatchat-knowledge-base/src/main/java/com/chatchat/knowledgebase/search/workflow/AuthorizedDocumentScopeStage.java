package com.chatchat.knowledgebase.search.workflow;

import com.chatchat.common.retrieval.AuthorizedRetrieval;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.document.DocumentSearchPlan;
import com.chatchat.knowledgebase.search.document.KnowledgeIrDocumentRecall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

/** Resolves the authoritative PostgreSQL document scope before index recall. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthorizedDocumentScopeStage implements DocumentRetrievalStage {
    private final KnowledgeIrDocumentRecall knowledgeIrDocumentRecall;
    private final SearchProperties properties;

    @Override public String id() { return "authorized-document-scope"; }
    @Override public int order() { return 200; }

    @Override
    public void execute(DocumentRetrievalWorkflowContext context) {
        DocumentSearchPlan plan = context.originalPlan();
        List<String> documentIds;
        String focusedQuery;
        try {
            SearchProperties.ProblemAnalysis workflow = properties.getProblemAnalysis();
            int routingLimit = workflow == null ? context.documentLimit()
                : Math.max(workflow.getRoutingMinDocuments(), Math.min(
                    workflow.getRoutingMaxDocuments(), context.documentLimit()));
            KnowledgeIrDocumentRecall.Recall recall = knowledgeIrDocumentRecall.recall(
                plan, Math.max(1, routingLimit));
            documentIds = recall.documentIds();
            focusedQuery = recall.focusedQuery();
        } catch (RuntimeException ex) {
            log.warn("knowledge_ir_document_recall_failed query='{}' error={}", plan.query(), ex.getMessage());
            context.stop();
            return;
        }
        context.authorizedDocumentIds(documentIds);
        context.focusedQuery(focusedQuery);
        if (properties.isDocumentFirstEnabled() && documentIds.isEmpty()) {
            log.info("document_recall_scope source=knowledge_ir allowedDocumentCount=0 action=stop_before_index");
            context.stop();
            return;
        }
        if (!documentIds.isEmpty()) {
            context.searchPlan(new DocumentSearchPlan(
                plan.query(), plan.topK(), plan.filters(), plan.scopedFileIds(),
                plan.effectiveScopedFileIds(), documentIds, String.join(",", documentIds),
                plan.intent(), plan.queryTokens(), plan.debug(), plan.permissionContext(),
                plan.visibilityContext(), plan.validation()));
            context.authorizationScope(AuthorizedRetrieval.Scope.restricted(
                plan.permissionContext().tenantId(), plan.permissionContext().userId(),
                new LinkedHashSet<>(plan.permissionContext().roles()), new LinkedHashSet<>(documentIds)));
        } else {
            context.authorizationScope(AuthorizedRetrieval.Scope.unrestricted(
                plan.permissionContext().tenantId(), plan.permissionContext().userId()));
        }
        log.info("document_recall_scope source={} allowedDocumentCount={}",
            documentIds.isEmpty() ? "legacy_metadata_acl" : "knowledge_ir", documentIds.size());
    }
}
