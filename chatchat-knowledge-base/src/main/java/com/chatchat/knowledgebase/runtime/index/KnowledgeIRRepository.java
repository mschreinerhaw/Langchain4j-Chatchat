package com.chatchat.knowledgebase.runtime.index;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeIRRepository extends JpaRepository<KnowledgeIREntity, Long> {
    List<KnowledgeIREntity> findByDocumentIdInAndActiveTrue(List<String> documentIds);
    List<KnowledgeIREntity> findTop1000ByTenantIdAndActiveTrue(String tenantId);
    void deleteByDocumentId(String documentId);
    long countByDocumentIdAndActiveTrue(String documentId);
}
