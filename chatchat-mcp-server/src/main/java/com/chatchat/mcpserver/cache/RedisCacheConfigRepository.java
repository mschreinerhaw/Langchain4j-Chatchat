package com.chatchat.mcpserver.cache;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RedisCacheConfigRepository extends JpaRepository<RedisCacheConfig, String> {
}
