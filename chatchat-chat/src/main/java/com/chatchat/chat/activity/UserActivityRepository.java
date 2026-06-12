package com.chatchat.chat.activity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserActivityRepository extends JpaRepository<UserActivityEntity, String> {

    List<UserActivityEntity> findByTenantIdAndUserIdAndTargetTypeOrderByCreatedAtDesc(
        String tenantId,
        String userId,
        String targetType,
        Pageable pageable
    );

    List<UserActivityEntity> findByTenantIdAndUserIdAndTargetTypeAndActionTypeOrderByCreatedAtDesc(
        String tenantId,
        String userId,
        String targetType,
        String actionType,
        Pageable pageable
    );
}
