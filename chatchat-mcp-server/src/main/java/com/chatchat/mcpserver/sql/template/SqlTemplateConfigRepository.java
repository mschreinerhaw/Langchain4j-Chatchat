package com.chatchat.mcpserver.sql.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SqlTemplateConfigRepository extends JpaRepository<SqlTemplateConfig, String> {

    Optional<SqlTemplateConfig> findByCode(String code);

    List<SqlTemplateConfig> findByEnabledTrueOrderByCodeAsc();

    @org.springframework.data.jpa.repository.Query("select t.publicationGroupId from SqlTemplateConfig t where t.id=:id and t.authorTenantId=:tenant and t.authorId=:actor")
    Optional<String> ownedPublicationGroup(@org.springframework.data.repository.query.Param("id") String id,
        @org.springframework.data.repository.query.Param("tenant") String tenant, @org.springframework.data.repository.query.Param("actor") String actor);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from SqlTemplateConfig t where t.id=:id")
    Optional<SqlTemplateConfig> lockPublication(@org.springframework.data.repository.query.Param("id") String id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    List<SqlTemplateConfig> findByPublicationGroupIdOrderByPublicationVersionAsc(String group);
    List<SqlTemplateConfig> findByAuthorTenantIdAndAuthorIdOrderByCreatedAtDesc(String tenant, String actor);
}
