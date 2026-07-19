package com.chatchat.chat.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantNotificationRecipientRepository extends JpaRepository<TenantNotificationRecipientEntity, String> {

    List<TenantNotificationRecipientEntity> findByTenantIdOrderByChannelTypeAsc(String tenantId);

    Optional<TenantNotificationRecipientEntity> findByTenantIdAndChannelType(String tenantId, String channelType);
}
