package com.chatchat.chat.activity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonalTodoRepository extends JpaRepository<PersonalTodoEntity, String> {

    List<PersonalTodoEntity> findByTenantIdAndUserIdOrderByCompletedAscImportantDescUpdatedAtDesc(
        String tenantId,
        String userId,
        Pageable pageable
    );

    List<PersonalTodoEntity> findByTenantIdAndUserIdAndCompletedFalseOrderByImportantDescUpdatedAtDesc(
        String tenantId,
        String userId,
        Pageable pageable
    );

    Optional<PersonalTodoEntity> findByIdAndTenantIdAndUserId(String id, String tenantId, String userId);
}
