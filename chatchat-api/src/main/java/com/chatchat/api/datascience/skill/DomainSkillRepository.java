package com.chatchat.api.datascience.skill;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface DomainSkillRepository extends JpaRepository<DomainSkillEntity, String> {
    @Query("""
        select s from DomainSkillEntity s
        where (s.tenantId = :tenantId or s.builtin = true)
          and (:category = '' or lower(s.category) = lower(:category))
          and (:status = '' or upper(s.status) = upper(:status))
          and (:keyword = '' or lower(s.name) like lower(concat('%', :keyword, '%'))
               or lower(s.description) like lower(concat('%', :keyword, '%'))
               or lower(s.category) like lower(concat('%', :keyword, '%'))
               or lower(s.searchText) like lower(concat('%', :keyword, '%')))
        order by s.updatedAt desc
        """)
    Page<DomainSkillEntity> search(@Param("tenantId") String tenantId,
                                   @Param("category") String category,
                                   @Param("status") String status,
                                   @Param("keyword") String keyword,
                                   Pageable pageable);

    @Query("select distinct s.category from DomainSkillEntity s where (s.tenantId = :tenantId or s.builtin = true) order by s.category")
    List<String> findCategories(@Param("tenantId") String tenantId);

    @Query("select count(s) from DomainSkillEntity s where (s.tenantId = :tenantId or s.builtin = true) and lower(s.category) = lower(:category)")
    long countVisibleByCategory(@Param("tenantId") String tenantId, @Param("category") String category);

    @Query("select count(s) from DomainSkillEntity s where s.tenantId = :tenantId or s.builtin = true")
    long countVisible(@Param("tenantId") String tenantId);

    Optional<DomainSkillEntity> findByIdAndTenantId(String id, String tenantId);

    @Query("select s from DomainSkillEntity s where s.id = :id and (s.tenantId = :tenantId or s.builtin = true)")
    Optional<DomainSkillEntity> findVisibleById(@Param("tenantId") String tenantId, @Param("id") String id);

    @Query("select count(s) from DomainSkillEntity s where (s.tenantId = :tenantId or s.builtin = true) and upper(s.status) = upper(:status)")
    long countVisibleByStatus(@Param("tenantId") String tenantId, @Param("status") String status);

    @Query("select s from DomainSkillEntity s where (s.tenantId = :tenantId or s.builtin = true) and s.id in :ids and s.status = :status")
    List<DomainSkillEntity> findVisibleByIdInAndStatus(@Param("tenantId") String tenantId,
                                                       @Param("ids") List<String> ids,
                                                       @Param("status") String status);

    @Query("select s from DomainSkillEntity s where (s.tenantId = :tenantId or s.builtin = true) and s.status = :status order by s.publishedAt desc")
    List<DomainSkillEntity> findVisibleByStatus(@Param("tenantId") String tenantId, @Param("status") String status);

    @Query("select s from DomainSkillEntity s where (s.tenantId = :tenantId or s.builtin = true) and lower(s.category) = lower(:category) and s.status = :status order by s.updatedAt desc")
    List<DomainSkillEntity> findVisibleByCategoryAndStatus(@Param("tenantId") String tenantId,
                                                           @Param("category") String category,
                                                           @Param("status") String status);
}
