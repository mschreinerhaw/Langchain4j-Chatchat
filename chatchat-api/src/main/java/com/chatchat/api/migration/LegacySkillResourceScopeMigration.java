package com.chatchat.api.migration;

import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.enterprise.entity.identity.SysTenant;
import com.chatchat.enterprise.repository.identity.SysTenantRepository;
import com.chatchat.enterprise.service.SkillResourceScopeSynchronizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Migrates pre-relationship-model Agent document bindings without rebuilding document indexes. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacySkillResourceScopeMigration {
    private final SkillCatalogService skills;
    private final SysTenantRepository tenants;
    private final SkillResourceScopeSynchronizationService scopes;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        int migrated = 0;
        int failed = 0;
        for (SysTenant tenant : tenants.findAllByOrderByTenantNameAsc()) {
            if (!"enabled".equalsIgnoreCase(tenant.getStatus())) continue;
            for (SkillDefinition skill : skills.list()) {
                try {
                    migrated += scopes.migrateIfMissing(tenant.getId(), skill.id(),
                        skill.boundDocumentIds(), skill.boundDocumentTags());
                } catch (RuntimeException error) {
                    failed++;
                    log.error("legacy_skill_resource_scope_migration_failed tenantId={} skillId={} error={}",
                        tenant.getId(), skill.id(), error.getMessage(), error);
                }
            }
        }
        log.info("legacy_skill_resource_scope_migration_complete migrated={} failed={}", migrated, failed);
    }
}
