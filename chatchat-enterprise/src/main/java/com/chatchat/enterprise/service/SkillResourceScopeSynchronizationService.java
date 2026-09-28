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
     * Migrates legacy Agent bindings only when the relationship model has not been configured yet.
     * Existing relationship rows remain authoritative and are never overwritten by this method.
     */
    @Transactional
    public int migrateIfMissing(String tenantId, String skillId,
                                List<String> documentIds, List<String> knowledgeBaseTags) {
        if (!repository.findByTenantIdAndSkillIdOrderByResourceTypeAscResourceIdAsc(tenantId, skillId).isEmpty()) {
            return 0;
        }
        return insert(tenantId, skillId, documentIds, knowledgeBaseTags);
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
        return insert(tenantId, skillId, documentIds, knowledgeBaseTags);
    }

    private int insert(String tenantId, String skillId,
                       List<String> documentIds, List<String> knowledgeBaseTags) {
        String tenant = required(tenantId, "tenantId");
        String skill = required(skillId, "skillId").toLowerCase(Locale.ROOT);
        Map<String, Binding> bindings = new LinkedHashMap<>();
        clean(documentIds, false).forEach(id ->
            bindings.put("DOCUMENT\u0000" + id, new Binding("DOCUMENT", id)));
        clean(knowledgeBaseTags, true).forEach(id ->
            bindings.put("KNOWLEDGE_BASE\u0000" + id, new Binding("KNOWLEDGE_BASE", id)));
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
