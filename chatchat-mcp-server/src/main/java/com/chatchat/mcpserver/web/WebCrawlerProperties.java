package com.chatchat.mcpserver.web;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "chatchat.mcp.web-crawler")
public class WebCrawlerProperties {

    private boolean enabled = true;

    private String userAgent = "ChatChat-MCP-Crawler/1.0";

    private int timeoutMs = 10000;

    private int maxBodyBytes = 1048576;

    private int maxHtmlChars = 200000;

    private int maxTextChars = 120000;

    private int chunkChars = 1200;

    private int chunkOverlapChars = 120;

    private int maxChunks = 20;

    private long cacheTtlSeconds = 604800;

    private boolean includeHtml = true;

    private String acceptLanguage = "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7";
}
