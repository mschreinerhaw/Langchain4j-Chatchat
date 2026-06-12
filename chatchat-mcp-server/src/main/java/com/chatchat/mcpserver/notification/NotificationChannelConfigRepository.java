package com.chatchat.mcpserver.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationChannelConfigRepository extends JpaRepository<NotificationChannelConfig, String> {

    Optional<NotificationChannelConfig> findByChannel(NotificationChannel channel);

    Optional<NotificationChannelConfig> findByToolName(String toolName);

    List<NotificationChannelConfig> findByEnabledTrueOrderByToolNameAsc();
}
