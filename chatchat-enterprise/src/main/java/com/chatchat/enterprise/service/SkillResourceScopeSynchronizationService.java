package com.chatchat.enterprise.service;

import com.chatchat.enterprise.entity.security.SkillResourceScope;
import com.chatchat.enterprise.repository.security.SkillResourceScopeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Keeps the database relationship model aligned with an Agent's knowledge bindings. */
@Service
@RequiredArgsConstructor
public class SkillResourceScopeSynchronizationService {
    private final SkillResourceScopeRepository repository;

    /**
     * Adds legacy Agent bindings that are missing from the relationship model.
     * Existing relationship rows remain authoritative and are never overwritten or re-enabled.
     */
    @Transactional
    public int migrateIfMissing(String tenantId, String skillId,
                                List<String> documentIds, List<String> knowledgeBaseTags) {
        String tenant = required(tenantId, "tenantId");
        String skill = required(skillId, "skillId").toLowerCase(Locale.ROOT);
        Map<String, Binding> desired = bindings(documentIds, knowledgeBaseTags);
        repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc(tenant, skill)
            .forEach(row -> desired.remove(key(row.getResourceType(), row.getResourceId())));
        return insert(tenant, skill, desired);
    }

    /** Replaces one Agent's relationship rows after its knowledge bindings are edited. */
    @Transactional
    public int synchronize(String tenantId, String skillId,
                           List<String> documentIds, List<String> knowledgeBaseTags) {
        List<SkillResourceScope> existing = repository
            .findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc(tenantId, skillId);
        if (!existing.isEmpty()) {
            repository.deleteAll(existing);
            repository.flush();
        }
        return insert(required(tenantId, "tenantId"),
            required(skillId, "skillId").toLowerCase(Locale.ROOT), bindings(documentIds, knowledgeBaseTags));
    }

    private Map<String, Binding> bindings(List<String> documentIds, List<String> knowledgeBaseTags) {
        Map<String, Binding> bindings = new LinkedHashMap<>();
        clean(documentIds, false).forEach(id ->
            bindings.put(key("DOCUMENT", id), new Binding("DOCUMENT", id)));
        clean(knowledgeBaseTags, true).forEach(id ->
            bindings.put(key("KNOWLEDGE_BASE", id), new Binding("KNOWLEDGE_BASE", id)));
        return bindings;
    }

    private int insert(String tenant, String skill, Map<String, Binding> bindings) {
        if (bindings.isEmpty()) {
            return 0;
        }
        List<SkillResourceScope> rows = bindings.values().stream().map(binding -> {
            SkillResourceScope row = new SkillResourceScope();
            row.setTenantId(tenant);
            row.setSkillId(skill);
            row.setResourceType(binding.type());
            row.setResourceId(binding.id());
            row.setEnabled(true);
            return row;
        }).toList();
        repository.saveAll(rows);
        return rows.size();
    }

    private String key(String type, String id) {
        String normalizedType = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        String normalizedId = id == null ? "" : id.trim();
        if ("KNOWLEDGE_BASE".equals(normalizedType)) normalizedId = normalizedId.toLowerCase(Locale.ROOT);
        return normalizedType + "\u0000" + normalizedId;
    }

    private List<String> clean(List<String> values, boolean lowerCase) {
        if (values == null) return List.of();
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .map(value -> lowerCase ? value.toLowerCase(Locale.ROOT) : value)
            .distinct()
            .toList();
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private record Binding(String type, String id) { }
}
