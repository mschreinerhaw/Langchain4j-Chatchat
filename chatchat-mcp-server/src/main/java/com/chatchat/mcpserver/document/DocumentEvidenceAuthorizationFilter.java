package com.chatchat.mcpserver.document;

import com.chatchat.knowledgebase.search.document.DocumentEvidenceChunk;
import com.chatchat.knowledgebase.search.document.DocumentOutlineItem;
import com.chatchat.knowledgebase.search.document.DocumentSearchHit;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import com.chatchat.knowledgebase.search.evidence.EvidenceContextFormatter;
import com.chatchat.knowledgebase.search.evidence.EvidenceReasoningEngine;
import com.chatchat.knowledgebase.search.evidence.EvidenceDecisionEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checks candidate documents against current API grants before evidence leaves MCP. */
@Component
@RequiredArgsConstructor
public class DocumentEvidenceAuthorizationFilter {
    private final ApiDocumentEvidenceClient apiClient;
    private final EvidenceContextFormatter formatter;
    private final Environment environment;
    private final EvidenceReasoningEngine reasoningEngine = new EvidenceReasoningEngine();
    private final EvidenceDecisionEngine decisionEngine = new EvidenceDecisionEngine();

    public boolean enabled() {
        return environment.getProperty("chatchat.mcp.server.document-search.api-authorization-enabled",
            Boolean.class, true);
    }

    public DocumentSearchResult filter(DocumentSearchRequest request, DocumentSearchResult result) {
        if (result == null || !enabled()) return result;
        if (request == null || request.tenantId() == null || request.tenantId().isBlank()
            || request.userId() == null || request.userId().isBlank()) {
            throw new IllegalStateException("Document authorization requires tenant and user");
        }
        Set<String> ids = new HashSet<>();
        if (result.results().stream().anyMatch(chunk -> chunk.fileId() == null || chunk.fileId().isBlank())
            || result.documents().stream().anyMatch(document -> document.docId() == null || document.docId().isBlank())
            || result.outline().stream().anyMatch(item -> item.docId() == null || item.docId().isBlank())) {
            throw new IllegalStateException("Document evidence contains an unidentified document");
        }
        result.results().stream().map(DocumentEvidenceChunk::fileId).filter(id -> id != null && !id.isBlank()).forEach(ids::add);
        result.documents().stream().map(DocumentSearchHit::docId).filter(id -> id != null && !id.isBlank()).forEach(ids::add);
        result.outline().stream().map(DocumentOutlineItem::docId).filter(id -> id != null && !id.isBlank()).forEach(ids::add);
        if (ids.isEmpty()) {
            if (result.context() != null && !result.context().isBlank()) {
                throw new IllegalStateException("Document evidence has no authorizable document ID");
            }
            return result;
        }
        Set<String> allowed = apiClient.allowedDocumentIds(request.tenantId(), request.userId(), ids);
        if (allowed.containsAll(ids)) return result;
        List<DocumentEvidenceChunk> chunks = result.results().stream()
            .filter(chunk -> allowed.contains(chunk.fileId())).toList();
        List<DocumentSearchHit> documents = result.documents().stream()
            .filter(document -> allowed.contains(document.docId())).toList();
        List<DocumentOutlineItem> outline = result.outline().stream()
            .filter(item -> allowed.contains(item.docId())).toList();
        // Original reasoning and decisions may quote removed evidence.
        DocumentSearchResult filtered = new DocumentSearchResult(result.contractVersion(), result.query(), result.intent(),
            chunks.size() + documents.size(), chunks, formatter.formatContext(chunks), formatter.citations(chunks),
            result.retrievalState(), null, List.of(),
            null, null, documents, outline, result.outlineSource(), null, null, null, null);
        var reasoning = reasoningEngine.reason(filtered, null);
        var decision = decisionEngine.decide(filtered, reasoning);
        return new DocumentSearchResult(filtered.contractVersion(), filtered.query(), filtered.intent(),
            filtered.total(), filtered.results(), filtered.context(), filtered.citations(),
            filtered.retrievalState(), filtered.evidenceQuality(), filtered.retrievalEvents(),
            filtered.matchType(), filtered.retrievalSemantics(), filtered.documents(), filtered.outline(),
            filtered.outlineSource(), filtered.expansionPolicy(), filtered.evidenceGovernancePolicy(),
            reasoning, decision);
    }
}
