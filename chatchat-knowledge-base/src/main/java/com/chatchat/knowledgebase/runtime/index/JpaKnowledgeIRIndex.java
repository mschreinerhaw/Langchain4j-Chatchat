package com.chatchat.knowledgebase.runtime.index;

import com.chatchat.common.knowledge.KnowledgeIR;
import com.chatchat.common.knowledge.KnowledgeIRIndexPort;
import com.chatchat.common.knowledge.KnowledgeIRQuery;
import com.chatchat.common.knowledge.KnowledgeIndexDocument;
import com.chatchat.common.knowledge.KnowledgeRule;
import com.chatchat.common.knowledge.KnowledgeSourceReference;
import com.chatchat.common.knowledge.KnowledgeType;
import com.chatchat.knowledgebase.search.query.SearchTokenizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** JPA-backed native Knowledge IR index with tenant/document/visibility enforcement. */
@Component
@RequiredArgsConstructor
public class JpaKnowledgeIRIndex implements KnowledgeIRIndexPort {

    private final KnowledgeIRRepository repository;
    private final ObjectMapper objectMapper;
    private final SearchTokenizer tokenizer;

    @Override
    @Transactional
    public void replaceDocument(KnowledgeIndexDocument document) {
        repository.deleteByDocumentId(document.documentId());
        // Enforce delete-before-insert for the (document_id, knowledge_id) unique key.
        repository.flush();
        long now = System.currentTimeMillis();
        List<KnowledgeIREntity> entities = document.units().stream()
            .map(unit -> toEntity(document, unit, now)).toList();
        repository.saveAll(entities);
    }

    @Override
    @Transactional
    public void deleteDocument(String documentId) {
        if (documentId != null && !documentId.isBlank()) repository.deleteByDocumentId(documentId.trim());
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeIR> search(KnowledgeIRQuery query) {
        List<KnowledgeIREntity> candidates = query.scope().documentIds().isEmpty()
            ? repository.findTop1000ByTenantIdAndActiveTrue(normalizeTenant(query.scope().tenantId()))
            : repository.findByDocumentIdInAndActiveTrue(query.scope().documentIds());
        Set<String> queryTokens = new LinkedHashSet<>(tokenizer.searchTokens(
            query.query() + " " + String.join(" ", query.queryHints())));
        return candidates.stream()
            .filter(entity -> visible(entity, query))
            .filter(entity -> query.types().isEmpty() || query.types().contains(parseType(entity.getKnowledgeType())))
            .filter(entity -> matchesTags(entity, query.scope().tags()))
            .map(entity -> new Scored(entity, score(entity, queryTokens)))
            .filter(scored -> queryTokens.isEmpty() || scored.score() > 0D)
            .sorted(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(scored -> scored.entity().getId()))
            .limit(query.limit())
            .map(scored -> toIr(scored.entity(), scored.score()))
            .toList();
    }

    private KnowledgeIREntity toEntity(KnowledgeIndexDocument document, KnowledgeIR unit, long now) {
        KnowledgeIREntity entity = new KnowledgeIREntity();
        entity.setKnowledgeId(unit.knowledgeId());
        entity.setDocumentId(document.documentId());
        entity.setTenantId(normalizeTenant(document.tenantId()));
        entity.setOwnerUserId(document.ownerUserId());
        entity.setVisibility(document.visibility());
        entity.setPermissionRolesJson(write(document.permissionRoles()));
        entity.setTagsJson(write(document.tags()));
        entity.setVersion(document.version());
        entity.setDomain(unit.domain());
        entity.setKnowledgeType(unit.type().name());
        entity.setTitle(unit.title());
        entity.setSemanticDescription(unit.semanticDescription());
        entity.setRulesJson(write(unit.rules()));
        entity.setConstraintsJson(write(unit.constraints()));
        entity.setApplicableIntentsJson(write(unit.applicableIntents()));
        entity.setRequiredInputsJson(write(unit.requiredInputs()));
        entity.setCompactRepresentation(unit.compactPromptRepresentation());
        entity.setSearchText(String.join(" ", unit.title(), unit.semanticDescription(),
            unit.compactPromptRepresentation(), String.join(" ", unit.constraints())));
        if (unit.source() != null) {
            entity.setSourceId(unit.source().sourceId());
            entity.setSourceChunkId(unit.source().chunkId());
            entity.setSourceDocumentName(unit.source().documentName());
            entity.setSourceSection(unit.source().section());
            entity.setSourceCitation(unit.source().citation());
        }
        entity.setRelevance(unit.relevance());
        entity.setActive(true);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private KnowledgeIR toIr(KnowledgeIREntity entity, double score) {
        KnowledgeSourceReference source = new KnowledgeSourceReference(
            entity.getSourceId(), entity.getDocumentId(), entity.getSourceChunkId(),
            entity.getSourceDocumentName(), entity.getSourceSection(), entity.getVersion(), entity.getSourceCitation());
        return new KnowledgeIR(
            entity.getKnowledgeId(), entity.getDomain(), parseType(entity.getKnowledgeType()),
            entity.getTitle(), entity.getSemanticDescription(), readRules(entity.getRulesJson()),
            readStrings(entity.getConstraintsJson()), readStrings(entity.getApplicableIntentsJson()),
            readStrings(entity.getRequiredInputsJson()), entity.getCompactRepresentation(), source,
            Math.max(entity.getRelevance() == null ? 0D : entity.getRelevance(), Math.min(score, 1D)));
    }

    private double score(KnowledgeIREntity entity, Set<String> queryTokens) {
        String haystack = entity.getSearchText() == null ? "" : entity.getSearchText().toLowerCase(Locale.ROOT);
        long matches = queryTokens.stream().filter(haystack::contains).count();
        double lexical = queryTokens.isEmpty() ? 0.5D : (double) matches / queryTokens.size();
        return lexical * 0.85D + Math.min(entity.getRelevance() == null ? 0.5D : entity.getRelevance(), 1D) * 0.15D;
    }

    private boolean visible(KnowledgeIREntity entity, KnowledgeIRQuery query) {
        if (!normalizeTenant(entity.getTenantId()).equals(normalizeTenant(query.scope().tenantId()))) return false;
        String visibility = entity.getVisibility() == null ? "tenant" : entity.getVisibility().toLowerCase(Locale.ROOT);
        if ("public".equals(visibility) || "tenant".equals(visibility)) return true;
        return entity.getOwnerUserId() != null && entity.getOwnerUserId().equals(query.scope().userId());
    }

    private boolean matchesTags(KnowledgeIREntity entity, List<String> requiredTags) {
        if (requiredTags == null || requiredTags.isEmpty()) return true;
        List<String> stored = readStrings(entity.getTagsJson()).stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
        return requiredTags.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(stored::contains);
    }

    private String normalizeTenant(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }

    private KnowledgeType parseType(String value) {
        try { return KnowledgeType.valueOf(value); }
        catch (RuntimeException ex) { return KnowledgeType.INTERPRETATION; }
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("Failed to serialize Knowledge IR", ex); }
    }

    private List<String> readStrings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception ex) { return List.of(); }
    }

    private List<KnowledgeRule> readRules(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception ex) { return List.of(); }
    }

    private record Scored(KnowledgeIREntity entity, double score) {
    }
}
