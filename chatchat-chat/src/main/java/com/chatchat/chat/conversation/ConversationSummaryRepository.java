package com.chatchat.chat.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConversationSummaryRepository extends JpaRepository<ConversationSummaryEntity, String> {

    Optional<ConversationSummaryEntity> findTopBySessionIdOrderByCreatedAtDesc(String sessionId);

    void deleteBySessionId(String sessionId);
}
