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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects the structured dynamic sections displayed on the CNINFO homepage. */
@Component
public class CninfoHomeNewsCollector implements NewsCollector {
    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final NewsRuntimeProperties properties;
    private final HttpClient client;

    @Autowired
    public CninfoHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper, NewsRuntimeProperties properties) {
        this(sink, objectMapper, properties,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    CninfoHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper, NewsRuntimeProperties properties,
                            HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.CNINFO_HOME;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        List<String> errors = new ArrayList<>();
        run(errors, counters, "latest announcements", () -> collectAnnouncements(source, counters));
        run(errors, counters, "interactive Q&A", () -> collectAnswers(source, counters));
        run(errors, counters, "research information", () -> collectResearch(source, counters));
        run(errors, counters, "online voting", () -> collectVoting(source, counters));
        run(errors, counters, "public information", () -> collectPublicInfo(source, counters));
        return new NewsCollectResult(context.executionId(), source.id(), counters.discovered, counters.accepted,
            counters.duplicate, counters.rejected, counters.failed, errors.isEmpty() ? null : String.join("; ", errors));
    }

    private void collectAnnouncements(NewsSource source, Counters counters) throws Exception {
        int limit = sectionLimit(source);
        int perColumn = Math.max(1, (limit + 1) / 2);
        for (String column : stringConfig(source, "announcementColumns", "szse,sse").split(",")) {
            String body = form(Map.ofEntries(
                Map.entry("pageNum", "1"), Map.entry("pageSize", String.valueOf(perColumn)),
                Map.entry("column", column.trim()), Map.entry("tabName", "fulltext"), Map.entry("plate", ""),
                Map.entry("stock", ""), Map.entry("searchkey", ""), Map.entry("secid", ""),
                Map.entry("category", ""), Map.entry("trade", ""), Map.entry("seDate", ""),
                Map.entry("sortName", ""), Map.entry("sortType", ""), Map.entry("isHLtitle", "true")));
            JsonNode items = postForm(stringConfig(source, "announcementApiUrl",
                "https://www.cninfo.com.cn/new/hisAnnouncement/query"), body, source).path("announcements");
            if (!items.isArray()) throw new IllegalStateException("announcement response has no announcements array");
            for (JsonNode item : items) {
                if (!capacity(counters)) return;
                acceptAnnouncement(source, counters, item);
            }
        }
    }

    private void acceptAnnouncement(NewsSource source, Counters counters, JsonNode item) {
        String title = item.path("announcementTitle").asText("").trim();
        String code = item.path("secCode").asText("").trim();
        String name = item.path("secName").asText("").trim();
        if (title.isBlank()) return;
        String attachment = absolute(stringConfig(source, "staticBaseUrl", "https://static.cninfo.com.cn/"),
            item.path("adjunctUrl").asText(""));
        Map<String, Object> metadata = metadata("cninfo-home-announcement", code, name);
        if (!attachment.isBlank()) {
            metadata.put("attachmentUrls", List.of(attachment));
            metadata.put("attachmentAllowedDomains", List.of("static.cninfo.com.cn"));
        }
        String content = "巨潮资讯首页最新公告。证券代码：" + code + "；证券简称：" + name
            + "；公告标题：" + title + "。公告全文见官方 PDF：" + attachment;
        accept(counters, new RawNewsItem(source, title, content, null, "巨潮资讯网", detailUrl(source, item),
            millis(item.path("announcementTime").asLong(0)), language(source), List.of("最新公告"),
            keywords(name, code), Map.copyOf(metadata)));
    }

    private void collectAnswers(NewsSource source, Counters counters) throws Exception {
        JsonNode items = getJson(stringConfig(source, "answersUrl",
            "https://www.cninfo.com.cn/new/companyReplies/getAnswersList"), source);
        requireArray(items, "answers");
        int count = 0;
        for (JsonNode item : items) {
            if (count++ >= sectionLimit(source) || !capacity(counters)) break;
            String name = item.path("shortName").asText("").trim();
            String code = item.path("stockCode").asText("").trim();
            String question = item.path("qContent").asText("").trim();
            String answer = item.path("rContent").asText("").trim();
            if (question.isBlank() && answer.isBlank()) continue;
            String title = name + "互动问答：" + abbreviate(question, 60);
            String content = "巨潮资讯互动问答。证券代码：" + code + "；证券简称：" + name
                + "。投资者提问：" + question + "；公司回复：" + answer;
            accept(counters, new RawNewsItem(source, title, content, question, name,
                https(item.path("qrDetailUrl").asText(source.entryUrl())), parseDateTime(item.path("rCreatedDate").asText(), source),
                language(source), List.of("互动问答"), keywords(name, code),
                Map.copyOf(metadata("cninfo-home-answers", code, name))));
        }
    }

    private void collectResearch(NewsSource source, Counters counters) throws Exception {
        JsonNode items = getJson(stringConfig(source, "researchUrl",
            "https://www.cninfo.com.cn/new/index/researchInformation"), source);
        requireArray(items, "research information");
        int count = 0;
        for (JsonNode item : items) {
            if (count++ >= sectionLimit(source) || !capacity(counters)) break;
            String title = item.path("announcementTitle").asText("").trim();
            String code = item.path("secCode").asText("").trim();
            String name = item.path("secName").asText("").trim();
            if (title.isBlank()) continue;
            String attachment = absolute(stringConfig(source, "staticBaseUrl", "https://static.cninfo.com.cn/"),
                item.path("adjunctUrl").asText(""));
            Map<String, Object> metadata = metadata("cninfo-home-research", code, name);
            if (!attachment.isBlank()) {
                metadata.put("attachmentUrls", List.of(attachment));
                metadata.put("attachmentAllowedDomains", List.of("static.cninfo.com.cn"));
            }
            String content = "巨潮资讯最新调研信息。证券代码：" + code + "；证券简称：" + name
                + "；调研文件：" + title + "。调研记录全文见官方 PDF：" + attachment;
            accept(counters, new RawNewsItem(source, title, content, null, name, detailUrl(source, item),
                millis(item.path("announcementTime").asLong(0)), language(source), List.of("调研信息"),
                keywords(name, code), Map.copyOf(metadata)));
        }
    }

    private void collectVoting(NewsSource source, Counters counters) throws Exception {
        JsonNode items = getJson(stringConfig(source, "votingUrl",
            "https://www.cninfo.com.cn/new/votingCompany/getMeetings"), source);
        requireArray(items, "voting meetings");
        int count = 0;
        for (JsonNode item : items) {
            if (count++ >= sectionLimit(source) || !capacity(counters)) break;
            String title = item.path("meetingName").asText("").trim();
            if (title.isBlank()) continue;
            Instant start = millis(item.path("startDate").asLong(0));
            Instant end = millis(item.path("endDate").asLong(0));
            String content = "巨潮资讯网络投票会议。会议名称：" + title + "；首项提案："
                + item.path("firstProposalName").asText("") + "；投票开始：" + start + "；投票结束：" + end
                + "；当前是否正在投票：" + item.path("isVoting").asBoolean(false) + "。";
            accept(counters, new RawNewsItem(source, title, content, null, "巨潮资讯网",
                https(item.path("linkUrlNew").asText(source.entryUrl())), start, language(source),
                List.of("网络投票"), List.of("股东会", "网络投票"),
                Map.of("transport", "cninfo-home-voting", "meetingId", item.path("meetingId").asText(""))));
        }
    }

    private void collectPublicInfo(NewsSource source, Counters counters) throws Exception {
        JsonNode items = getJson(stringConfig(source, "publicInfoUrl",
            "https://www.cninfo.com.cn/data/centerSpecial/getIndexPublicInfo?market="), source).path("publicInfo");
        requireArray(items, "public information");
        int count = 0;
        for (JsonNode item : items) {
            if (count++ >= sectionLimit(source) || !capacity(counters)) break;
            String code = item.path("SECCODE").asText("").trim();
            String name = item.path("SECNAME").asText("").trim();
            String reason = item.path("F002V").asText("").trim();
            if (reason.isBlank()) continue;
            String title = name + "（" + code + "）公开信息：" + abbreviate(reason, 60);
            String content = "巨潮资讯公开信息。证券代码：" + code + "；证券简称：" + name
                + "；信息公开原因：" + reason + "；交易日期：" + item.path("TRADEDATE").asText("")
                + "。该记录来自巨潮资讯首页公开信息栏目，详细交易公开数据以巨潮资讯官方页面为准。";
            accept(counters, new RawNewsItem(source, title, content, null, "巨潮资讯网",
                "https://www.cninfo.com.cn/new/commonUrl?url=data%2Fgongkai", parseDateTime(item.path("TRADEDATE").asText(), source),
                language(source), List.of("公开信息"), keywords(name, code),
                Map.copyOf(metadata("cninfo-home-public-info", code, name))));
        }
    }

    private JsonNode getJson(String url, NewsSource source) throws Exception {
        return objectMapper.readTree(send(request(url, source).GET().build(), url));
    }
    private JsonNode postForm(String url, String body, NewsSource source) throws Exception {
        HttpRequest request = request(url, source).header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return objectMapper.readTree(send(request, url));
    }
    private HttpRequest.Builder request(String url, NewsSource source) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 20_000)))
            .header("Accept", "application/json,text/plain,*/*")
            .header("Referer", source.entryUrl())
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0");
    }
    private String send(HttpRequest request, String url) throws Exception {
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private String detailUrl(NewsSource source, JsonNode item) {
        return base(source.entryUrl()) + "/new/disclosure/detail?stockCode=" + encode(item.path("secCode").asText(""))
            + "&announcementId=" + encode(item.path("announcementId").asText(""))
            + "&orgId=" + encode(item.path("orgId").asText(""))
            + "&announcementTime=" + item.path("announcementTime").asLong(0);
    }
    private String base(String url) { URI uri = URI.create(url); return uri.getScheme() + "://" + uri.getAuthority(); }
    private String absolute(String base, String value) {
        if (value == null || value.isBlank()) return "";
        URI uri = URI.create(value); if (uri.isAbsolute()) return https(uri.toString());
        return URI.create(base.endsWith("/") ? base : base + "/").resolve(value.replaceFirst("^/+", "")).toString();
    }
    private String https(String value) { return value == null ? "" : value.replaceFirst("^http:", "https:"); }
    private String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
    }
    private String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    private Instant millis(long value) { return value > 0 ? Instant.ofEpochMilli(value) : null; }
    private Instant parseDateTime(String value, NewsSource source) {
        if (value == null || value.isBlank()) return null;
        try {
            String normalized = value.trim().length() >= 19 ? value.trim().substring(0, 19) : value.trim() + " 00:00:00";
            return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"))).toInstant();
        } catch (Exception ex) { return null; }
    }
    private Map<String, Object> metadata(String transport, String code, String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transport", transport); result.put("securityCode", code); result.put("securityName", name);
        return result;
    }
    private List<String> keywords(String name, String code) {
        if (name == null || name.isBlank()) return code == null || code.isBlank() ? List.of() : List.of(code);
        return code == null || code.isBlank() ? List.of(name) : List.of(name, code);
    }
    private String abbreviate(String value, int max) {
        if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max) + "…";
    }
    private void requireArray(JsonNode value, String label) {
        if (!value.isArray()) throw new IllegalStateException(label + " response is not an array");
    }
    private void accept(Counters counters, RawNewsItem item) {
        counters.discovered++; counters.add(sink.accept(item));
    }
    private boolean capacity(Counters counters) { return counters.discovered < properties.getMaxItemsPerRun(); }
    private int sectionLimit(NewsSource source) {
        return Math.max(1, Math.min(intConfig(source, "sectionItemLimit", 20), properties.getMaxItemsPerRun()));
    }
    private String language(NewsSource source) { return stringConfig(source, "language", "zh-CN"); }
    private String stringConfig(NewsSource source, String key, String fallback) {
        Object value = source.configuration().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
    private int intConfig(NewsSource source, String key, int fallback) {
        Object value = source.configuration().get(key); return value instanceof Number number ? number.intValue() : fallback;
    }
    private void run(List<String> errors, Counters counters, String label, CheckedAction action) {
        try { action.run(); } catch (Exception ex) { counters.failed++; errors.add(label + ": " + ex.getMessage()); }
    }
    private interface CheckedAction { void run() throws Exception; }
    private static class Counters {
        int discovered, accepted, duplicate, rejected, failed;
        void add(NewsAcceptance result) {
            if (result == NewsAcceptance.ACCEPTED) accepted++;
            else if (result == NewsAcceptance.DUPLICATE) duplicate++;
            else rejected++;
        }
    }
}
