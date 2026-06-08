package com.chatchat.chat.conversation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageIndexRepository extends JpaRepository<ChatMessageIndexEntity, String> {

    List<ChatMessageIndexEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<ChatMessageIndexEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    void deleteBySessionId(String sessionId);
}
