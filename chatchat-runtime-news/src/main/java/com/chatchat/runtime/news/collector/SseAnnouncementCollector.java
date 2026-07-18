package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects the dynamic company-announcement feed used by the official SSE page. */
@Component
public class SseAnnouncementCollector implements NewsCollector {
    private static final String DEFAULT_API_URL =
        "https://query.sse.com.cn/security/stock/queryCompanyBulletin.do";
    private static final String DEFAULT_STATIC_BASE_URL = "https://static.sse.com.cn";
    private static final String DEFAULT_SECURITY_TYPES = "0101,120100,020100,020200,120200";

    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final NewsRuntimeProperties properties;
    private final HttpClient client;

    @Autowired
    public SseAnnouncementCollector(NewsItemSink sink, ObjectMapper objectMapper,
                                    NewsRuntimeProperties properties) {
        this(sink, objectMapper, properties,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    SseAnnouncementCollector(NewsItemSink sink, ObjectMapper objectMapper,
                             NewsRuntimeProperties properties, HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.SSE_ANNOUNCEMENTS;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        int discovered = 0;
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        try {
            JsonNode items = request(source, context).path("pageHelp").path("data");
            if (!items.isArray()) {
                throw new IllegalStateException("SSE response has no pageHelp.data array");
            }
            for (JsonNode item : items) {
                if (discovered >= properties.getMaxItemsPerRun()) break;
                discovered++;
                NewsAcceptance result = sink.accept(toRawItem(source, item));
                if (result == NewsAcceptance.ACCEPTED) accepted++;
                else if (result == NewsAcceptance.DUPLICATE) duplicate++;
                else rejected++;
            }
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, 0, null);
        } catch (Exception ex) {
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, 1, ex.getMessage());
        }
    }

    private JsonNode request(NewsSource source, NewsCollectContext context) throws Exception {
        int pageSize = Math.max(1, Math.min(intConfig(source, "itemLimit", 100),
            Math.min(100, properties.getMaxItemsPerRun())));
        ZoneId zone = ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"));
        LocalDate endDate = context.startedAt().atZone(zone).toLocalDate();
        LocalDate beginDate = endDate.minusDays(Math.max(0, intConfig(source, "lookbackDays", 3)));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("isPagination", "true");
        parameters.put("productId", "");
        parameters.put("keyWord", "");
        parameters.put("securityType", stringConfig(source, "securityType", DEFAULT_SECURITY_TYPES));
        parameters.put("reportType2", "");
        parameters.put("reportType", "ALL");
        parameters.put("beginDate", beginDate.toString());
        parameters.put("endDate", endDate.toString());
        parameters.put("pageHelp.pageSize", String.valueOf(pageSize));
        parameters.put("pageHelp.pageCount", "50");
        parameters.put("pageHelp.pageNo", "1");
        parameters.put("pageHelp.beginPage", "1");
        parameters.put("pageHelp.cacheSize", "1");
        parameters.put("pageHelp.endPage", "5");
        String url = stringConfig(source, "apiUrl", DEFAULT_API_URL) + "?" + query(parameters);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 20_000)))
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from SSE announcement API");
        }
        return objectMapper.readTree(response.body());
    }

    private RawNewsItem toRawItem(NewsSource source, JsonNode item) {
        String title = item.path("TITLE").asText("").trim();
        String securityCode = item.path("SECURITY_CODE").asText("").trim();
        String securityName = item.path("SECURITY_NAME").asText("").trim();
        String publishDate = item.path("SSEDATE").asText("").trim();
        String bulletinType = item.path("BULLETIN_TYPE").asText("").trim();
        String attachmentUrl = absolute(stringConfig(source, "staticBaseUrl", DEFAULT_STATIC_BASE_URL),
            item.path("URL").asText(""));
        String content = "上海证券交易所上市公司公告。证券代码：" + securityCode + "；证券简称：" + securityName
            + "；公告类型：" + bulletinType + "；公告标题：" + title + "；公告发布日期：" + publishDate
            + "。一级页面由上交所动态公告接口提供，公告全文及二级文档见官方 PDF 附件：" + attachmentUrl;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transport", "sse-company-bulletin-api");
        metadata.put("securityCode", securityCode);
        metadata.put("securityName", securityName);
        metadata.put("bulletinType", bulletinType);
        if (!attachmentUrl.isBlank()) {
            metadata.put("attachmentUrls", List.of(attachmentUrl));
            metadata.put("attachmentAllowedDomains", List.of("static.sse.com.cn", "sse.com.cn"));
        }
        return new RawNewsItem(source, title, content, null, "上海证券交易所",
            attachmentUrl.isBlank() ? source.entryUrl() : attachmentUrl, parseDate(publishDate, source),
            stringConfig(source, "language", "zh-CN"), List.of("上市公司公告"),
            securityName.isBlank() ? List.of(securityCode) : List.of(securityName, securityCode), Map.copyOf(metadata));
    }

    private Instant parseDate(String value, NewsSource source) {
        try {
            return LocalDate.parse(value).atStartOfDay(
                ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"))).toInstant();
        } catch (Exception ex) {
            return null;
        }
    }

    private String absolute(String base, String path) {
        if (path == null || path.isBlank()) return "";
        URI value = URI.create(path);
        if (value.isAbsolute()) return value.toString();
        URI root = URI.create(base.endsWith("/") ? base : base + "/");
        return root.resolve(path.startsWith("/") ? path.substring(1) : path).toString();
    }

    private String query(Map<String, String> values) {
        return values.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String stringConfig(NewsSource source, String key, String fallback) {
        Object value = source.configuration().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private int intConfig(NewsSource source, String key, int fallback) {
        Object value = source.configuration().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
