package com.chatchat.mcpserver.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationChannelConfigRepository extends JpaRepository<NotificationChannelConfig, String> {

    boolean existsByToolName(String toolName);

    boolean existsByToolNameAndIdNot(String toolName, String id);

    List<NotificationChannelConfig> findByEnabledTrueOrderByToolNameAsc();
}
