package com.chatchat.mcpserver.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "chatchat.mcp.lucene")
public class LuceneSearchProperties {

    private boolean enabled = true;

    private String indexDir = "./data/lucene/mcp";

    private int maxResults = 50;
}
