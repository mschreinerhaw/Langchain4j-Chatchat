package com.chatchat.chat.uiartifact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TrendSemanticKeywordRepository
    extends JpaRepository<TrendSemanticKeywordEntity, TrendSemanticKeywordKey> {

    List<TrendSemanticKeywordEntity> findByTenantIdOrderBySortOrderAscKeywordAsc(String tenantId);

    @Modifying(flushAutomatically = true)
    @Query("delete from TrendSemanticKeywordEntity keyword where keyword.tenantId = :tenantId")
    void deleteByTenantId(@Param("tenantId") String tenantId);
}
