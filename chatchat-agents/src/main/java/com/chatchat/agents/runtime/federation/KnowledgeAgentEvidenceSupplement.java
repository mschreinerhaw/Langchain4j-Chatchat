package com.chatchat.agents.runtime.federation;

import com.chatchat.common.knowledge.model.KnowledgeScope;
import com.chatchat.common.knowledge.runtime.KnowledgeRequest;
import com.chatchat.common.knowledge.runtime.KnowledgeSkillExecutionContext;
import com.chatchat.common.knowledge.skill.KnowledgeSkillInstance;
import com.chatchat.common.knowledge.skill.KnowledgeSkillType;
import com.chatchat.common.runtime.agent.*;
import com.chatchat.common.runtime.analysis.evidence.AnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.StructuredDataEvidence;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.model.AnalysisScope;
import com.chatchat.common.runtime.analysis.plan.EvidenceRequirement;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.agents.runtime.analysis.workflow.AnalysisOperatorRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ObjectProvider<AnalysisOperatorRegistry> operators;

    @Autowired
    public KnowledgeAgentEvidenceSupplement(KnowledgeSkillExecutionUnit skills,
                                            ObjectProvider<SkillExecutionScopePort> scopes,
                                            ObjectProvider<AnalysisOperatorRegistry> operators) {
        this.skills = skills;
        this.scopes = scopes;
        this.operators = operators;
    }

    @Override
    public List<AnalysisEvidence> supplement(AgentDescriptor agent, AgentExecutionRequest request,
                                             EvidenceRequirement requirement) {
        if ("STRUCTURED_DATA".equals(requirement.type()))
            return supplementStructuredData(agent, request);
        KnowledgeSkillType type;
        try { type = KnowledgeSkillType.valueOf(requirement.type()); }
        catch (RuntimeException invalid) { return List.of(); }
        if (!strings(agent.metadata().get("supplementSkillTypes")).contains(type.name())) return List.of();
        Object localSkillId = request.metadata().get("localSkillId");
        SkillExecutionScopePort resolver = scopes.getIfAvailable();
        if (!(localSkillId instanceof String skillId) || skillId.isBlank() || resolver == null) return List.of();
        if (!grantedSkill(agent, skillId)) return List.of();
        boolean expandDocuments = allowDocumentSupplement(agent);
        var effective = resolver.resolve(request.scope().tenantId(), request.scope().userId(), skillId,
            expandDocuments ? List.of() : strings(request.metadata().get("documentIds")),
            strings(request.metadata().get("documentTags")));
        if (!effective.skillAllowed() || effective.documentIds().contains(SkillExecutionScopePort.DENIED_DOCUMENT_ID))
            return List.of();
        List<String> documents = grantedDocuments(agent, effective.documentIds());
        if (!expandDocuments && agent.metadata().get("analysisGrants") instanceof Map<?, ?>
            && documents.isEmpty()) return List.of();
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

    private List<AnalysisEvidence> supplementStructuredData(AgentDescriptor agent,
                                                            AgentExecutionRequest request) {
        if (!strings(agent.metadata().get("supplementCapabilities")).contains("STRUCTURED_DATA"))
            return List.of();
        Object localSkillId = request.metadata().get("localSkillId");
        SkillExecutionScopePort resolver = scopes.getIfAvailable();
        AnalysisOperatorRegistry registry = operators.getIfAvailable();
        if (!(localSkillId instanceof String skillId) || skillId.isBlank()
            || resolver == null || registry == null) return List.of();
        if (!grantedSkill(agent, skillId) || !grantedStructuredData(agent)) return List.of();
        var effective = resolver.resolve(request.scope().tenantId(), request.scope().userId(), skillId,
            strings(request.metadata().get("documentIds")), strings(request.metadata().get("documentTags")));
        if (!effective.skillAllowed() || effective.documentIds().contains(SkillExecutionScopePort.DENIED_DOCUMENT_ID))
            return List.of();
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        for (String key : List.of("runtime.analysis.dataTemplateId", "runtime.analysis.dataAssetName",
            "runtime.analysis.dataEnvironment", "runtime.analysis.dataParameters")) {
            Object value = request.metadata().get(key);
            if (value != null) attributes.put(key, value);
        }
        if (!attributes.containsKey("runtime.analysis.dataTemplateId")) return List.of();
        List<String> documents = grantedDocuments(agent, effective.documentIds());
        AnalysisContext context = new AnalysisContext(request.task().instruction(), request.scope(), skillId,
            documents, effective.tags(), effective.roles(),
            new AnalysisIntent("SUPPLEMENT_STRUCTURED_DATA", List.of(),
                Set.of(AnalysisCapability.STRUCTURED_DATA), "UNSPECIFIED", true), attributes);
        var operator = registry.resolve(AnalysisCapability.STRUCTURED_DATA, context).orElse(null);
        if (operator == null) return List.of();
        var result = operator.execute(context, new AnalysisScope(request.scope().tenantId(),
            request.scope().userId(), effective.roles(), documents, Map.of("skillId", skillId)), null);
        if (result.evidence().size() != 1 || !(result.evidence().get(0) instanceof StructuredDataEvidence data)
            || !request.scope().tenantId().equals(data.attributes().get("tenantId"))) return List.of();
        return List.of(data);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> items)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : items) if (item instanceof String text && !text.isBlank()) result.add(text.trim());
        return List.copyOf(result);
    }

    private boolean grantedSkill(AgentDescriptor agent, String skillId) {
        if (!(agent.metadata().get("analysisGrants") instanceof Map<?, ?> grants)) return true;
        return strings(grants.get("skillIds")).contains(skillId);
    }

    private List<String> grantedDocuments(AgentDescriptor agent, List<String> documents) {
        if (!(agent.metadata().get("analysisGrants") instanceof Map<?, ?> grants)) return documents;
        if (Boolean.TRUE.equals(grants.get("allowDocumentSupplement"))) return documents;
        List<String> allowed = strings(grants.get("documentIds"));
        return documents.stream().filter(allowed::contains).toList();
    }

    private boolean grantedStructuredData(AgentDescriptor agent) {
        if (!(agent.metadata().get("analysisGrants") instanceof Map<?, ?> grants)) return true;
        return Boolean.TRUE.equals(grants.get("allowDataSupplement"));
    }

    private boolean allowDocumentSupplement(AgentDescriptor agent) {
        return agent.metadata().get("analysisGrants") instanceof Map<?, ?> grants
            && Boolean.TRUE.equals(grants.get("allowDocumentSupplement"));
    }
}
