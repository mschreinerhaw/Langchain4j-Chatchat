package com.chatchat.chat.conversation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageIndexRepository extends JpaRepository<ChatMessageIndexEntity, String> {

    /**
     * Finds the by session id order by created at asc.
     *
     * @param sessionId the session id value
     * @return the matching by session id order by created at asc
     */
    List<ChatMessageIndexEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * Finds the by session id order by created at desc.
     *
     * @param sessionId the session id value
     * @param pageable the pageable value
     * @return the matching by session id order by created at desc
     */
    List<ChatMessageIndexEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    /**
     * Deletes the by session id.
     *
     * @param sessionId the session id value
     */
    void deleteBySessionId(String sessionId);
}
