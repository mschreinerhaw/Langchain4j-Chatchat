package com.chatchat.chat.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    List<ChatSessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);
}
