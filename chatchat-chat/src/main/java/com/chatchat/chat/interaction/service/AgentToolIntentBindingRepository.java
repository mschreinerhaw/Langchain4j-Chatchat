package com.chatchat.chat.interaction.service;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentToolIntentBindingRepository extends JpaRepository<AgentToolIntentBinding, String> {
    List<AgentToolIntentBinding> findByEnabledTrueOrderByInputKeyAsc();
}
