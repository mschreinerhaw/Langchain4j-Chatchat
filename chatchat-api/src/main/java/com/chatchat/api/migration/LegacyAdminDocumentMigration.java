package com.chatchat.api.migration;

import com.chatchat.enterprise.entity.identity.SysUser;
import com.chatchat.enterprise.repository.identity.SysUserRepository;
import com.chatchat.knowledgebase.search.index.RocksDbSearchStore;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Upgrades pre-RBAC documents whose owner was stored as the literal username {@code admin}. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyAdminDocumentMigration {

    private static final String LEGACY_ADMIN_USER = "admin";

    private final RocksDbSearchStore store;
    private final SearchService searchService;
    private final SysUserRepository users;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        Optional<SysUser> adminResult = users.findByUsername(LEGACY_ADMIN_USER);
        if (adminResult.isEmpty()) {
            log.warn("legacy_admin_document_migration_skipped reason=admin_user_missing");
            return;
        }
        SysUser admin = adminResult.get();
        int examined = 0;
        int migrated = 0;
        int failed = 0;
        for (String docId : store.listDocumentIds(0)) {
            Optional<SearchDocument> result = store.get(docId);
            if (result.isEmpty()) {
                continue;
            }
            examined++;
            SearchDocument document = result.get();
            if (!LEGACY_ADMIN_USER.equalsIgnoreCase(trim(document.getUserId()))) {
                continue;
            }
            try {
                if (searchService.reassignDocumentOwner(docId, admin.getTenantId(), admin.getId()).isPresent()) {
                    migrated++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.error("legacy_admin_document_migration_failed docId={} error={}",
                    docId, ex.getMessage(), ex);
            }
        }
        log.info("legacy_admin_document_migration_complete examined={} migrated={} failed={} adminUserId={} tenantId={}",
            examined, migrated, failed, admin.getId(), admin.getTenantId());
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
