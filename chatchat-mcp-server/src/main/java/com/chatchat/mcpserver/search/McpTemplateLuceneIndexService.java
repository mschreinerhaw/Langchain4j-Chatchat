package com.chatchat.mcpserver.search;

import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class McpTemplateLuceneIndexService {

    public Map<String, Object> refreshAll() {
        return Map.of("refreshed", true, "index", "template", "mode", "in_memory");
    }

    public Map<String, Object> upsertDatabaseQueryTemplates(List<DatabaseQueryConfig> templates) {
        int count = templates == null ? 0 : templates.size();
        return Map.of("refreshed", true, "index", "template", "upserted", count, "mode", "in_memory");
    }
}
