package com.chatchat.tools.web;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "chatchat.tools.web-crawler")
public class WebCrawlerProperties {

    private boolean enabled = true;

    private String userAgent = "ChatChat-MCP-Crawler/1.0";

    private int timeoutMs = 10000;

    private int maxBodyBytes = 1048576;

    private int maxHtmlChars = 200000;

    private int maxTextChars = 120000;

    private DocumentExtractionProperties documentExtraction = new DocumentExtractionProperties();

    private EvidenceProperties evidence = new EvidenceProperties();

    private int chunkChars = 1200;

    private int chunkOverlapChars = 120;

    private int maxChunks = 20;

    private long cacheTtlSeconds = 604800;

    private boolean includeHtml = true;

    private int maxFollowUrls = 5;

    private String acceptLanguage = "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7";

    private String defaultMode = "browser";

    private BrowserProperties browser = new BrowserProperties();

    private ProxyPoolProperties proxyPool = new ProxyPoolProperties();

    @Data
    public static class DocumentExtractionProperties {

        private boolean enabled = true;

        private int maxDocumentBytes = 10485760;

        private int maxExtractedChars = 0;

        private List<String> supportedExtensions = new ArrayList<>(List.of(
            "pdf",
            "doc",
            "docx",
            "xls",
            "xlsx",
            "xlsm",
            "csv",
            "rtf",
            "ppt",
            "pptx"
        ));
    }

    @Data
    public static class EvidenceProperties {

        private boolean enabled = true;

        private int maxSummaryChars = 500;

        private int maxEvidenceBlocks = 5;

        private int maxEvidenceBlockChars = 900;

        private int maxKeywords = 10;

        private int minUsefulTextChars = 120;
    }

    @Data
    public static class BrowserProperties {

        private boolean enabled = true;

        private String browsersPath = "playwright-browsers";

        private boolean skipBrowserDownload = false;

        private int navigationTimeoutMs = 0;

        private String accept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

        private String acceptLanguage = "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7";

        private String referer = "";

        private String secChUa = "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"";

        private String secChUaMobile = "?0";

        private String secChUaPlatform = "\"Windows\"";

        private List<String> userAgents = new ArrayList<>(List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 13_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15"
        ));

        private Map<String, String> headers = new LinkedHashMap<>();
    }

    @Data
    public static class ProxyPoolProperties {

        private boolean enabled = false;

        private String defaultPool = "default";

        private List<ProxyConfig> proxies = new ArrayList<>();
    }

    @Data
    public static class ProxyConfig {

        private String id;

        private String pool = "default";

        private String type = "HTTP";

        private String host;

        private int port;

        private String username;

        private String password;

        private List<String> tenantIds = new ArrayList<>();

        private List<String> taskIds = new ArrayList<>();
    }
}
