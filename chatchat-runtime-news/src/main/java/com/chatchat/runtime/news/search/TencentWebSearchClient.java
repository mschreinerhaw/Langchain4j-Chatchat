package com.chatchat.runtime.news.search;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Internal Tencent Cloud WSA SearchPro adapter. It is deliberately not a tool
 * provider: callers can only reach it through the existing web_search flow.
 */
@Component
public class TencentWebSearchClient {
    private static final Logger log = LoggerFactory.getLogger(TencentWebSearchClient.class);
    private static final String SERVICE = "wsa";
    private static final String ACTION = "SearchPro";
    private static final String VERSION = "2025-05-08";
    private static final String ALGORITHM = "TC3-HMAC-SHA256";
    private static final DateTimeFormatter UTC_DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final Set<String> HOTSPOT_TERMS = Set.of(
        "最新", "当前", "近期", "今天", "今日", "热点", "热搜", "趋势", "动态", "突发",
        "latest", "current", "recent", "today", "breaking", "trending", "trend");

    private final ObjectMapper mapper;
    private final NewsRuntimeProperties properties;
    private final HttpClient client;

    @Autowired
    public TencentWebSearchClient(ObjectMapper mapper, NewsRuntimeProperties properties) {
        this(mapper, properties, HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    TencentWebSearchClient(ObjectMapper mapper, NewsRuntimeProperties properties, HttpClient client) {
        this.mapper = mapper;
        this.properties = properties;
        this.client = client;
    }

    public boolean enabled() {
        NewsRuntimeProperties.WebSearch config = properties.getWebSearch();
        return config != null && config.isEnabled()
            && !blank(config.getSecretId()) && !blank(config.getSecretKey());
    }

    public SearchResponse search(String query, int requested) throws Exception {
        return search(query, requested, null);
    }

    private SearchResponse search(String query, int requested, Integer fallbackMode) throws Exception {
        if (!enabled()) return new SearchResponse(List.of(), "", "");
        NewsRuntimeProperties.WebSearch config = properties.getWebSearch();
        URI endpoint = URI.create(config.getEndpoint());
        String host = endpoint.getHost();
        if (blank(host)) throw new IllegalArgumentException("Invalid Tencent WSA endpoint");

        int count = Math.max(1, Math.min(Math.min(config.getMaxResults(), 50), requested));
        Instant now = Instant.now();
        int mode = fallbackMode == null
            ? Math.max(0, Math.min(2, config.getMode()))
            : fallbackMode;
        ObjectNode payload = mapper.createObjectNode().put("Query", query);
        // WSA defines natural retrieval as the default. Some service editions reject even an
        // explicit Mode=0, so only send the optional field for richer retrieval modes.
        if (mode > 0) payload.put("Mode", mode);
        if (config.isRequestCountEnabled()) payload.put("Cnt", count);
        if (isHotspotQuery(query) && config.getHotspotLookbackDays() > 0) {
            payload.put("FromTime", now.minus(Duration.ofDays(config.getHotspotLookbackDays())).getEpochSecond());
            payload.put("ToTime", now.getEpochSecond());
        }
        String body = mapper.writeValueAsString(payload);
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofMillis(Math.max(1_000, config.getTimeoutMillis())))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-TC-Action", ACTION)
            .header("X-TC-Version", VERSION)
            .header("X-TC-Timestamp", String.valueOf(now.getEpochSecond()))
            .header("Authorization", authorization(host, body, now, config))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!blank(config.getRegion())) request.header("X-TC-Region", config.getRegion().trim());

        String auditQuery = auditQuery(query);
        long startedAt = System.nanoTime();
        log.info(
            "tencentWsaHttpCallStart endpoint={} action={} version={} query=\"{}\" requested={} count={} mode={} "
                + "hotspotWindow={} timeoutMs={}",
            endpoint, ACTION, VERSION, auditQuery, requested, count, mode,
            payload.has("FromTime"), Math.max(1_000, config.getTimeoutMillis())
        );

        HttpResponse<byte[]> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception ex) {
            log.warn(
                "tencentWsaHttpCallFailed endpoint={} action={} query=\"{}\" stage=transport elapsedMs={} error={}",
                endpoint, ACTION, auditQuery, elapsedMillis(startedAt), safeMessage(ex)
            );
            throw ex;
        }

        JsonNode root;
        try {
            root = mapper.readTree(response.body()).path("Response");
        } catch (Exception ex) {
            log.warn(
                "tencentWsaHttpCallFailed endpoint={} action={} query=\"{}\" stage=response_parse "
                    + "httpStatus={} responseBytes={} elapsedMs={} error={}",
                endpoint, ACTION, auditQuery, response.statusCode(), response.body().length,
                elapsedMillis(startedAt), safeMessage(ex)
            );
            throw ex;
        }
        JsonNode error = root.path("Error");
        if (response.statusCode() < 200 || response.statusCode() >= 300 || !error.isMissingNode()) {
            String message = error.path("Message").asText("HTTP " + response.statusCode());
            String errorCode = error.path("Code").asText("");
            log.warn(
                "tencentWsaHttpCallFailed endpoint={} action={} query=\"{}\" stage=provider_response "
                    + "httpStatus={} errorCode={} requestId={} elapsedMs={} error={}",
                endpoint, ACTION, auditQuery, response.statusCode(), errorCode,
                root.path("RequestId").asText(""), elapsedMillis(startedAt), auditText(message, 300)
            );
            if (fallbackMode == null && mode != 0
                && "InvalidParameter".equalsIgnoreCase(errorCode)
                && message.toLowerCase(Locale.ROOT).contains("mode")) {
                log.warn("tencentWsaModeFallback query=\"{}\" configuredMode={} fallbackMode=0",
                    auditQuery, mode);
                return search(query, requested, 0);
            }
            throw new IllegalStateException("Tencent WSA SearchPro failed: " + message);
        }
        List<SearchPage> pages = new ArrayList<>();
        for (JsonNode raw : root.path("Pages")) {
            try {
                JsonNode page = raw.isTextual() ? mapper.readTree(raw.asText()) : raw;
                String title = text(page, "title");
                String url = text(page, "url");
                if (title.isBlank() && url.isBlank()) continue;
                String snippet = firstNonBlank(text(page, "content"), text(page, "passage"));
                int maxChars = Math.max(100, config.getMaxSnippetChars());
                if (snippet.length() > maxChars) snippet = snippet.substring(0, maxChars) + "…";
                pages.add(new SearchPage(title, url, text(page, "date"), snippet,
                    text(page, "site"), page.path("score").asDouble(0D)));
                if (pages.size() >= count) break;
            } catch (Exception ignored) {
                // SearchPro encodes every page independently; one malformed page must not discard the batch.
            }
        }
        String requestId = root.path("RequestId").asText("");
        String responseVersion = root.path("Version").asText("");
        log.info(
            "tencentWsaHttpCallCompleted endpoint={} action={} query=\"{}\" httpStatus={} requestId={} "
                + "responseVersion={} resultCount={} responseBytes={} elapsedMs={}",
            endpoint, ACTION, auditQuery, response.statusCode(), requestId, responseVersion,
            pages.size(), response.body().length, elapsedMillis(startedAt)
        );
        return new SearchResponse(List.copyOf(pages), requestId, responseVersion);
    }

    private String authorization(String host, String body, Instant now,
                                 NewsRuntimeProperties.WebSearch config) throws Exception {
        String canonicalHeaders = "content-type:application/json; charset=utf-8\n"
            + "host:" + host.toLowerCase(Locale.ROOT) + "\n"
            + "x-tc-action:" + ACTION.toLowerCase(Locale.ROOT) + "\n";
        String signedHeaders = "content-type;host;x-tc-action";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + sha256(body);
        String date = UTC_DATE.format(now);
        String scope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = ALGORITHM + "\n" + now.getEpochSecond() + "\n" + scope + "\n"
            + sha256(canonicalRequest);
        byte[] secretDate = hmac(("TC3" + config.getSecretKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, SERVICE);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));
        return ALGORITHM + " Credential=" + config.getSecretId() + "/" + scope
            + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    private byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean isHotspotQuery(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return HOTSPOT_TERMS.stream().anyMatch(normalized::contains);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String auditQuery(String query) {
        return auditText(query, 200);
    }

    private String auditText(String value, int maxLength) {
        String normalized = value == null ? "" : value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .trim();
        if (normalized.length() <= maxLength) return normalized;
        return normalized.substring(0, maxLength) + "...";
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private String safeMessage(Exception ex) {
        if (ex == null) return "unknown";
        String message = ex.getMessage();
        return auditText(message == null || message.isBlank() ? ex.getClass().getSimpleName() : message, 300);
    }

    public record SearchPage(String title, String url, String date, String snippet,
                             String site, double score) { }

    public record SearchResponse(List<SearchPage> pages, String requestId, String version) { }
}
