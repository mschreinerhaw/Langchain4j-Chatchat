package com.chatchat.mcpserver.search;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class McpAssetLuceneIndexService {

    public Map<String, Object> refreshAll() {
        return Map.of("refreshed", true, "index", "asset", "mode", "in_memory");
    }
}
