package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.EmbedLoginToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmbedLoginTokenRepository extends JpaRepository<EmbedLoginToken, String> {

    Optional<EmbedLoginToken> findByToken(String token);

    List<EmbedLoginToken> findByUserIdOrderByCreatedAtDesc(String userId);
}
