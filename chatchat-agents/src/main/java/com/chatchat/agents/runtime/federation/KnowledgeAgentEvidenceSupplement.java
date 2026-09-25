package com.chatchat.agents.runtime.federation;

import com.chatchat.common.knowledge.model.KnowledgeScope;
import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.common.knowledge.runtime.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.skill.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.skill.KnowledgeSkillType;
import com.chatchat.common.runtime.agent.*;
import com.chatchat.common.runtime.analysis.evidence.AnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.plan.EvidenceRequirement;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Uses only the platform's whitelisted Knowledge Skills and caller-owned scope selectors. */
@Component
public class KnowledgeAgentEvidenceSupplement implements AgentEvidenceSupplementPort {
    private final KnowledgeSkillExecutionUnit skills;
    private final ObjectProvider<SkillExecutionScopePort> scopes;

    public KnowledgeAgentEvidenceSupplement(KnowledgeSkillExecutionUnit skills,
                                            ObjectProvider<SkillExecutionScopePort> scopes) {
        this.skills = skills;
        this.scopes = scopes;
    }

    @Override
    public List<AnalysisEvidence> supplement(AgentDescriptor agent, AgentExecutionRequest request,
                                             EvidenceRequirement requirement) {
        KnowledgeSkillType type;
        try { type = KnowledgeSkillType.valueOf(requirement.type()); }
        catch (RuntimeException invalid) { return List.of(); }
        if (!strings(agent.metadata().get("supplementSkillTypes")).contains(type.name())) return List.of();
        Object localSkillId = request.metadata().get("localSkillId");
        SkillExecutionScopePort resolver = scopes.getIfAvailable();
        if (!(localSkillId instanceof String skillId) || skillId.isBlank() || resolver == null) return List.of();
        var effective = resolver.resolve(request.scope().tenantId(), request.scope().userId(), skillId,
            strings(request.metadata().get("documentIds")), strings(request.metadata().get("documentTags")));
        if (!effective.skillAllowed() || effective.documentIds().contains(SkillExecutionScopePort.DENIED_DOCUMENT_ID))
            return List.of();
        List<String> documents = effective.documentIds();
        List<String> tags = effective.tags();
        List<String> domains = strings(request.metadata().get("knowledgeDomains"));
        if (documents.isEmpty() && tags.isEmpty()) return List.of();
        KnowledgeScope scope = new KnowledgeScope(skillId, request.scope().tenantId(),
            request.scope().userId(), documents, tags, domains, effective.roles());
        KnowledgeRequest knowledgeRequest = new KnowledgeRequest(null, request.task().instruction(),
            request.task().type(), 800, scope, Set.of(type), Map.of());
        KnowledgeSkillInstance instance = new KnowledgeSkillInstance(
            request.executionId() + ":" + type.name(), type, domains.isEmpty() ? "general" : domains.get(0),
            request.task().instruction(), List.of(), 50, 800, Map.of());
        var result = skills.execute(new KnowledgeSkillExecutionContext(knowledgeRequest, instance), request.scope());
        List<AnalysisEvidence> evidence = new ArrayList<>();
        int limit = Math.min(5, Math.max(1, requirement.minimumCount()));
        for (var unit : result.knowledgeUnits()) {
            if (evidence.size() >= limit) break;
            var source = unit.source();
            String content = unit.compactPromptRepresentation().isBlank()
                ? unit.semanticDescription() : unit.compactPromptRepresentation();
            if (content.isBlank()) continue;
            String documentId = source == null ? "" : source.documentId();
            if (documentId == null || documentId.isBlank()) continue;
            if (!documents.isEmpty() && !documents.contains(documentId)) continue;
            evidence.add(new DocumentAnalysisEvidence("skill:" + unit.knowledgeId(), documentId,
                source == null ? "" : source.chunkId(), source == null ? "" : source.documentName(),
                source == null ? "" : source.section(), source == null ? "" : source.citation(),
                content, unit.relevance(), Map.of("remoteProjection", Map.of("text", content,
                    "citation", source == null || source.citation() == null ? "" : source.citation()),
                    "localSkillType", type.name())));
        }
        return List.copyOf(evidence);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> items)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : items) if (item instanceof String text && !text.isBlank()) result.add(text.trim());
        return List.copyOf(result);
    }
}
