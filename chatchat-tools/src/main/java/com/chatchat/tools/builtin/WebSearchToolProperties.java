package com.chatchat.tools.builtin;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "chatchat.tools.web-search")
public class WebSearchToolProperties {

    private boolean enabled = true;

    private String provider = "duckduckgo_html";

    private String endpoint = "https://duckduckgo.com/html/";

    private String userAgent = "ChatChat-MCP-Server/1.0";

    private int timeoutMs = 10000;

    private int maxResults = 10;

    private boolean fetchPages = true;

    private int maxPagesToFetch = 3;

    private int pageExcerptChars = 2500;

    private int pageMaxBytes = 1048576;

    private boolean fallbackEnabled = true;
}
