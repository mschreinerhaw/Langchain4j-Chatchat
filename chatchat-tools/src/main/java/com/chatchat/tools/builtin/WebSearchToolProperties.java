package com.chatchat.tools.builtin;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private BrowserProperties browser = new BrowserProperties();

    private ProxyPoolProperties proxyPool = new ProxyPoolProperties();

    private RetryProperties retry = new RetryProperties();

    private RateLimitProperties rateLimit = new RateLimitProperties();

    private CookieProperties cookie = new CookieProperties();

    private AllowListProperties allowList = new AllowListProperties();

    private AuditProperties audit = new AuditProperties();

    @Data
    public static class BrowserProperties {

        private boolean enabled = true;

        private String accept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

        private String acceptLanguage = "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7";

        private String referer = "https://www.bing.com/";

        private String secChUa = "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"";

        private String secChUaMobile = "?0";

        private String secChUaPlatform = "\"Windows\"";

        private String tlsFingerprintProfile = "jdk-default";

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

    @Data
    public static class RetryProperties {

        private int maxAttempts = 3;

        private List<Integer> retryStatusCodes = new ArrayList<>(List.of(403, 408, 429, 500, 502, 503, 504));

        private List<String> retryBodyKeywords = new ArrayList<>(List.of("captcha", "verify you are human", "too many requests"));

        private long backoffMs = 500;
    }

    @Data
    public static class RateLimitProperties {

        private boolean enabled = true;

        private int maxConcurrency = 5;

        private double qps = 1.0;

        private int dailyLimit = 1000;
    }

    @Data
    public static class CookieProperties {

        private boolean enabled = true;

        private boolean persist = false;

        private String storePath = "./data/web-search-cookies.json";

        private String isolation = "proxy_task";
    }

    @Data
    public static class AllowListProperties {

        private boolean enabled = false;

        private List<String> domains = new ArrayList<>(List.of(
            "bing.com",
            "www.bing.com",
            "duckduckgo.com",
            "www.duckduckgo.com",
            "localhost",
            "127.0.0.1"
        ));
    }

    @Data
    public static class AuditProperties {

        private boolean enabled = true;

        private boolean includeInResult = true;
    }
}
