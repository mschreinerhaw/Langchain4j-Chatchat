package com.chatchat.knowledgebase.runtime.index;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface KnowledgeIRRepository extends JpaRepository<KnowledgeIREntity, Long> {
    List<KnowledgeIREntity> findByDocumentIdInAndActiveTrue(List<String> documentIds);
    List<KnowledgeIREntity> findTop1000ByTenantIdAndActiveTrue(String tenantId);
    @Query("select u from KnowledgeIREntity u where u.tenantId = :tenantId and u.active = true "
        + "and lower(u.tagsJson) like :pattern order by u.id")
    List<KnowledgeIREntity> findByTenantAndTagPattern(@Param("tenantId") String tenantId,
                                                       @Param("pattern") String pattern,
                                                       Pageable pageable);
    @Query("select u from KnowledgeIREntity u where u.tenantId = :tenantId and u.active = true and ("
        + "lower(u.title) like :pattern or "
        + "lower(u.sourceSection) like :pattern or "
        + "lower(u.sourceDocumentName) like :pattern or "
        + "lower(u.searchText) like :pattern)")
    List<KnowledgeIREntity> findMatchingUnits(@Param("tenantId") String tenantId,
                                              @Param("pattern") String pattern,
                                              Pageable pageable);
    @Query("select u from KnowledgeIREntity u where u.tenantId = :tenantId and u.active = true and ("
        + "lower(u.title) like :pattern or "
        + "lower(u.sourceSection) like :pattern or "
        + "lower(u.sourceDocumentName) like :pattern)")
    List<KnowledgeIREntity> findMatchingHeadings(@Param("tenantId") String tenantId,
                                                 @Param("pattern") String pattern,
                                                 Pageable pageable);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from KnowledgeIREntity u where u.documentId = :documentId")
    int deleteAllByDocumentId(@Param("documentId") String documentId);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update KnowledgeIREntity u set u.tenantId = :tenantId, u.ownerUserId = :ownerUserId, "
        + "u.visibility = :visibility, u.permissionRolesJson = :permissionRolesJson, u.updatedAt = :updatedAt "
        + "where u.documentId = :documentId")
    int updateAuthorizationByDocumentId(@Param("documentId") String documentId,
                                        @Param("tenantId") String tenantId,
                                        @Param("ownerUserId") String ownerUserId,
                                        @Param("visibility") String visibility,
                                        @Param("permissionRolesJson") String permissionRolesJson,
                                        @Param("updatedAt") long updatedAt);
    long countByDocumentIdAndActiveTrue(String documentId);
}
