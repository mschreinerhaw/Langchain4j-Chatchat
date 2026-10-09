package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.evidence.*;
import com.chatchat.common.tool.ToolInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/** Model-facing retrieval projection. No prompts, thinking, invocation arguments or arbitrary metadata are returned. */
@Component
public class GovernedExecutionTraceRetrieval implements ExecutionTraceRetrievalPort {
    private final EvidenceStorePort store;
    private final SkillExecutionScopePort scopes;
    private final SkillCatalogService skills;
    private final McpToolCatalogQueryPort catalog;
    private final ToolRegistry tools;
    private final EnterpriseToolRuntimePolicyProvider policy;
    private final ObjectMapper mapper;

    public GovernedExecutionTraceRetrieval(EvidenceStorePort store, SkillExecutionScopePort scopes,
            SkillCatalogService skills, McpToolCatalogQueryPort catalog, ToolRegistry tools,
            EnterpriseToolRuntimePolicyProvider policy, ObjectMapper mapper) {
        this.store = store; this.scopes = scopes; this.skills = skills; this.catalog = catalog;
        this.tools = tools; this.policy = policy; this.mapper = mapper;
    }

    @Override public List<Summary> search(KernelDataScope scope, String query, int limit) {
        if (query == null || query.isBlank() || query.length() > 256)
            throw new IllegalArgumentException("A bounded trace search query is required");
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return store.query(new EvidenceQuery(scope, List.of(), List.of(), 0, 0, 1000)).stream()
            .filter(this::authorized).map(this::project).filter(Objects::nonNull)
            .filter(detail -> detail.summary().excerpt().toLowerCase(Locale.ROOT).contains(needle)
                || String.valueOf(detail.observation().get("content")).toLowerCase(Locale.ROOT).contains(needle))
            .limit(Math.max(1, Math.min(limit, 50))).map(Detail::summary).toList();
    }

    @Override public Optional<Detail> get(KernelDataScope scope, String evidenceId) {
        return store.find(scope, evidenceId).filter(this::authorized).map(this::project);
    }

    private boolean authorized(EvidenceRecord record) {
        return authorized(record, new HashMap<>(), new HashSet<>(), 0);
    }

    private boolean authorized(EvidenceRecord record, Map<String, Boolean> checked, Set<String> path, int depth) {
        if (depth > 8 || checked.size() >= 256 || !path.add(record.evidenceId())) return false;
        try {
            if (checked.containsKey(record.evidenceId())) return checked.get(record.evidenceId());
            boolean allowed = authorizedSource(record, checked, path, depth);
            checked.put(record.evidenceId(), allowed);
            return allowed;
        } finally { path.remove(record.evidenceId()); }
    }

    private boolean authorizedSource(EvidenceRecord record, Map<String, Boolean> checked, Set<String> path, int depth) {
        String skillId = String.valueOf(record.metadata().getOrDefault("skillId", ""));
        if (skillId.isBlank()) return false;
        String document = record.metadata().get("documentId") instanceof String value ? value : null;
        var scope = scopes.resolve(record.scope().tenantId(), record.scope().userId(), skillId,
            document == null ? List.of() : List.of(document), List.of());
        if (!scope.skillAllowed()) return false;
        if ("DOCUMENT_SEARCH".equals(record.evidenceType()))
            return document != null && scope.documentIds().contains(document) && !scope.documentScopeDenied();
        if ("COMPUTATION".equals(record.evidenceType())) {
            var lineage = store.lineage(record.scope(), record.evidenceId()).orElse(null);
            return lineage != null && !lineage.parentEvidenceIds().isEmpty() && lineage.parentEvidenceIds().size() <= 32
                && lineage.parentEvidenceIds().stream().allMatch(id -> store.find(record.scope(), id)
                    .filter(parent -> authorized(parent, checked, path, depth + 1)).isPresent());
        }
        if (!Set.of("TOOL_CALL", "STRUCTURED_DATA").contains(record.evidenceType())) return false;
        String name = String.valueOf(record.metadata().getOrDefault("toolName", ""));
        var metadata = tools.getToolMetadata(name);
        if (metadata == null || !metadata.isUserVisible() || !metadata.isAgentCompatible()
            || !"active".equalsIgnoreCase(metadata.getPublicationStatus())
            || !"read".equalsIgnoreCase(metadata.getOperationType())
            || Set.of("high", "forbidden").contains(String.valueOf(metadata.getRiskLevel()).toLowerCase(Locale.ROOT))
            || metadata.getVisibleTenantIds() != null && !metadata.getVisibleTenantIds().isEmpty()
                && !metadata.getVisibleTenantIds().contains(record.scope().tenantId())) return false;
        Object raw = record.payload().get("attributes");
        if (!(raw instanceof Map<?, ?> attrs) || !(attrs.get("toolContractHash") instanceof String contractHash)
            || !AnalysisToolContractIdentity.matches(contractHash, attrs.get("toolContractIdentityVersion"), metadata, mapper)
            || !(attrs.get("authorizationParameters") instanceof Map<?, ?> parameters)) return false;
        var skill = skills.list().stream().filter(item -> skillId.equals(item.id())).findFirst().orElse(null);
        if (skill == null) return false;
        if (!AnalysisToolBindingPolicy.bound(skill, name, catalog)) return false;
        Map<String, Object> arguments = new LinkedHashMap<>();
        parameters.forEach((key, value) -> arguments.put(String.valueOf(key), value));
        var decision = policy.resolve(ToolRuntimeRequest.builder().toolName(name).runtimeMode("analysis")
            .tenantId(record.scope().tenantId()).userId(record.scope().userId()).allowedTools(List.of(name))
            .toolInput(ToolInput.builder().userId(record.scope().userId()).parameters(arguments).build())
            .attributes(Map.of("analysisSkillId", skillId)).build(), metadata);
        return decision != null && Boolean.TRUE.equals(decision.allowed())
            && decision.executionAction() != com.chatchat.agents.runtime.tool.ToolRuntimeAction.DENY;
    }

    private Detail project(EvidenceRecord record) {
        String content = String.valueOf(record.payload().getOrDefault("content", ""));
        if (content.length() > 32_768) return null;
        Object observation;
        try { observation = sanitize(mapper.readValue(content, Object.class), 0); }
        catch (Exception notJson) {
            // Plain text is supported only for document evidence; tool output must be structured.
            if (!"DOCUMENT_SEARCH".equals(record.evidenceType())) return null;
            observation = content;
        }
        if (observation == null) return null;
        String text;
        try { text = observation instanceof String value ? value : mapper.writeValueAsString(observation); }
        catch (Exception invalid) { return null; }
        var summary = new Summary(record.evidenceId(), record.evidenceType(), record.sourceNode(),
            text.substring(0, Math.min(120, text.length())), record.occurredAtEpochMs(),
            Boolean.TRUE.equals(record.metadata().get("verified")));
        var lineage = store.lineage(record.scope(), record.evidenceId()).orElse(null);
        return new Detail(summary, Map.of("content", observation), lineage, "UNTRUSTED_OBSERVATION_REQUIRES_CURRENT_VERIFICATION");
    }

    private Object sanitize(Object value, int depth) {
        if (depth > 16) return "[depth limit]";
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> safe = new LinkedHashMap<>();
            values.forEach((key, item) -> {
                String name = String.valueOf(key);
                String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (List.of("password", "passwd", "secret", "token", "credential", "authorization",
                        "apikey", "cookie", "privatekey", "thinking", "chainofthought", "systemprompt", "instructions")
                    .stream().anyMatch(normalized::contains)) safe.put(name, "[redacted]");
                else safe.put(name, item == null ? null : sanitize(item, depth + 1));
            });
            return safe;
        }
        if (value instanceof List<?> values) return values.stream().limit(1000).map(item -> sanitize(item, depth + 1)).toList();
        return value;
    }
}
