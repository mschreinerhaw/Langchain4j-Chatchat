package com.chatchat.mcpserver.cache;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DatabaseQueryCacheConfigRepository extends JpaRepository<DatabaseQueryCacheConfig, String> {
}
