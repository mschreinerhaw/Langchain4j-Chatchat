package com.chatchat.tools.web;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class WebPageAnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(WebPageAnalyzeService.class);

    private static final Pattern DETAIL_PATH_PATTERN = Pattern.compile("(?i)(/\\d{4}[-_/]?\\d{0,2}[-_/]?\\d{0,2}/|/article/|/news/|/content/|/detail/|\\.(s?html?|aspx?)($|[?#]))");
    private static final Pattern SEARCH_TEXT_PATTERN = Pattern.compile("(?i)(search|query|keyword|keywords|q|wd|word|sousuo|so|suggest|autocomplete|检索|搜索|查询)");
    private static final Pattern PAGE_TEXT_PATTERN = Pattern.compile("(?i)(next|prev|page|p=|page=|分页|下一页|上一页|更多)");
    private static final Pattern ASSET_PATH_PATTERN = Pattern.compile("(?i)\\.(pdf|doc|docx|xls|xlsx|ppt|pptx|zip|rar)($|[?#])");
    private static final Pattern LOW_VALUE_LINK_TEXT_PATTERN = Pattern.compile(
        "(?i)^(关注|登录|注册|开户|下载|客户端|移动客户端|手机版|桌面版|专业版|增值版|免费版|iphone版|android版|ipad版|app|分享|收藏|评论|反馈|广告|推广|帮助|导航|返回顶部)$");
    private static final Pattern PROMOTIONAL_URL_PATTERN = Pattern.compile(
        "(?i)(acttg\\.|marketing\\.|/tg\\.aspx|/activity|/download|/client|/mobile|/app|kaihu|kh_|开户|广告|推广)");

    private final WebCrawlerProperties properties;

    public WebPageAnalyzeService(WebCrawlerProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> analyze(String url, String query, int maxLinks, int timeoutMs) {
        String normalizedUrl = normalizeUrl(url);
        int limit = Math.max(1, Math.min(maxLinks <= 0 ? 50 : maxLinks, 200));
        int timeout = timeoutMs < 0 ? properties.getTimeoutMs() : timeoutMs;
        long startedAt = System.currentTimeMillis();
        log.info("Web page analyze started url={} query={} maxLinks={} timeoutMs={}",
            normalizedUrl, firstNonBlank(query, ""), limit, timeout <= 0 ? "unbounded" : timeout);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageUrl", normalizedUrl);
        result.put("query", firstNonBlank(query, ""));
        result.put("maxLinks", limit);
        List<String> errors = new ArrayList<>();
        try {
            Connection.Response response = Jsoup.connect(normalizedUrl)
                .userAgent(selectUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                .timeout(timeout <= 0 ? 0 : Math.max(1000, timeout))
                .ignoreHttpErrors(true)
                .ignoreContentType(true)
                .followRedirects(true)
                .execute();
            Document document = response.parse();
            String finalUrl = firstNonBlank(response.url() == null ? null : response.url().toString(), normalizedUrl);
            PageAnalysis analysis = inspect(document, normalizedUrl, finalUrl, response.statusCode(), query, limit);
            logAnalysisResult(analysis, startedAt);
            return analysis.toMap();
        } catch (Exception ex) {
            errors.add("page_analyze_failed: " + ex.getMessage());
            result.put("errors", errors);
            result.put("recommendedActions", List.of(Map.of(
                "action", "crawl_url",
                "url", normalizedUrl,
                "confidence", 0.35,
                "reason", "Page analysis failed; crawl only if this URL is already known to be relevant."
            )));
            log.warn("Web page analyze failed url={} query={} durationMs={} error={}",
                normalizedUrl, firstNonBlank(query, ""), Math.max(0L, System.currentTimeMillis() - startedAt), ex.getMessage());
            return result;
        }
    }

    private void logAnalysisResult(PageAnalysis analysis, long startedAt) {
        log.info("Web page analyze succeeded url={} finalUrl={} status={} pageType={} title={} searchForms={} links={} pagination={} recommendedActions={} durationMs={}",
            analysis.pageUrl,
            analysis.finalUrl,
            analysis.status,
            analysis.pageType,
            shorten(analysis.title, 120),
            analysis.searchForms.size(),
            analysis.links.size(),
            analysis.pagination.size(),
            analysis.recommendedActions.size(),
            Math.max(0L, System.currentTimeMillis() - startedAt));
        int index = 0;
        for (Map<String, Object> link : analysis.links.stream().limit(8).toList()) {
            log.info("Web page analyze link[{}] type={} score={} sameSite={} url={} text={} snippet={}",
                index++,
                link.get("type"),
                link.get("score"),
                link.get("sameSite"),
                link.get("url"),
                shorten(stringValue(link.get("text")), 100),
                shorten(stringValue(link.get("snippet")), 220));
        }
        index = 0;
        for (Map<String, Object> action : analysis.recommendedActions.stream().limit(5).toList()) {
            log.info("Web page analyze action[{}] action={} confidence={} url={} reason={}",
                index++,
                action.get("action"),
                action.get("confidence"),
                action.get("url"),
                shorten(stringValue(action.get("reason")), 160));
        }
    }

    private PageAnalysis inspect(Document document,
                                 String requestedUrl,
                                 String finalUrl,
                                 int statusCode,
                                 String query,
                                 int maxLinks) {
        PageAnalysis analysis = new PageAnalysis();
        analysis.pageUrl = requestedUrl;
        analysis.finalUrl = finalUrl;
        analysis.status = statusCode;
        analysis.title = firstNonBlank(document.title(), "");
        analysis.description = firstNonBlank(
            attr(document, "meta[name=description]", "content"),
            attr(document, "meta[property=og:description]", "content"),
            ""
        );
        analysis.canonicalUrl = firstNonBlank(attr(document, "link[rel=canonical]", "href"), finalUrl);
        analysis.domain = hostOf(finalUrl);
        analysis.language = firstNonBlank(document.selectFirst("html") == null ? null : document.selectFirst("html").attr("lang"), "");
        analysis.searchForms = searchForms(document, finalUrl, query);
        analysis.pagination = paginationLinks(document, finalUrl);
        analysis.links = rankedLinks(document, finalUrl, query, maxLinks);
        analysis.pageType = classifyPage(finalUrl, document, analysis);
        analysis.recommendedActions = recommendedActions(analysis, query);
        return analysis;
    }

    private List<Map<String, Object>> searchForms(Document document, String baseUrl, String query) {
        List<Map<String, Object>> forms = new ArrayList<>();
        for (Element form : document.select("form")) {
            Element input = bestSearchInput(form);
            String formText = String.join(" ", form.attr("action"), form.attr("id"), form.attr("class"), form.text());
            if (input == null && !SEARCH_TEXT_PATTERN.matcher(formText).find()) {
                continue;
            }
            String action = firstNonBlank(form.absUrl("action"), baseUrl);
            String method = firstNonBlank(form.attr("method"), "GET").toUpperCase(Locale.ROOT);
            String inputName = input == null ? "q" : firstNonBlank(input.attr("name"), input.attr("id"), "q");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("action", action);
            item.put("method", method);
            item.put("inputName", inputName);
            item.put("sampleUrl", "GET".equals(method) ? withQuery(action, inputName, query) : action);
            item.put("confidence", input == null ? 0.62 : 0.86);
            forms.add(item);
        }
        return forms.stream().limit(12).toList();
    }

    private Element bestSearchInput(Element scope) {
        Element best = null;
        int bestScore = 0;
        for (Element input : scope.select("input,textarea")) {
            String type = firstNonBlank(input.attr("type"), "text").toLowerCase(Locale.ROOT);
            if (Set.of("hidden", "submit", "button", "reset", "checkbox", "radio", "file", "image").contains(type)) {
                continue;
            }
            String text = String.join(" ", type, input.attr("name"), input.attr("id"), input.attr("class"), input.attr("placeholder"), input.attr("aria-label"));
            int score = SEARCH_TEXT_PATTERN.matcher(text).find() ? 4 : 0;
            if ("search".equals(type)) {
                score += 5;
            }
            if (score > bestScore) {
                best = input;
                bestScore = score;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private List<Map<String, Object>> rankedLinks(Document document, String baseUrl, String query, int maxLinks) {
        Map<String, Map<String, Object>> dedup = new LinkedHashMap<>();
        int candidates = 0;
        int ignoredLowValue = 0;
        for (Element link : document.select("a[href]")) {
            String href = firstNonBlank(link.absUrl("href"), link.attr("href"));
            if (!isHttpUrl(href)) {
                continue;
            }
            candidates++;
            String text = cleanText(firstNonBlank(link.text(), link.attr("title"), link.attr("aria-label"), ""));
            if (text.isBlank() && !sameSite(baseUrl, href)) {
                continue;
            }
            String snippet = linkSnippet(link, text);
            String type = linkType(baseUrl, href, text);
            if (shouldIgnoreLink(link, baseUrl, href, text, snippet, type, query)) {
                ignoredLowValue++;
                continue;
            }
            double score = linkScore(baseUrl, href, text, snippet, type, query);
            String cleanUrl = stripFragment(href);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", cleanUrl);
            item.put("text", text);
            item.put("title", firstNonBlank(link.attr("title"), ""));
            item.put("snippet", snippet);
            item.put("type", type);
            item.put("score", round(score));
            item.put("sameSite", sameSite(baseUrl, href));
            item.put("reason", linkReason(type, score, query));
            Map<String, Object> existing = dedup.get(cleanUrl);
            if (existing == null
                || score > numberValue(existing.get("score"))
                || stringValue(existing.get("snippet")).length() < snippet.length()) {
                dedup.put(cleanUrl, item);
            }
        }
        List<Map<String, Object>> ranked = dedup.values().stream()
            .sorted(Comparator.comparingDouble(item -> -numberValue(item.get("score"))))
            .limit(maxLinks)
            .toList();
        log.info("Web page analyze link filtering url={} candidates={} ignoredLowValue={} deduped={} returned={}",
            baseUrl, candidates, ignoredLowValue, dedup.size(), ranked.size());
        return ranked;
    }

    private List<Map<String, Object>> paginationLinks(Document document, String baseUrl) {
        List<Map<String, Object>> pages = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : document.select("a[href]")) {
            String href = firstNonBlank(link.absUrl("href"), link.attr("href"));
            String text = cleanText(firstNonBlank(link.text(), link.attr("title"), link.attr("aria-label"), ""));
            if (!isHttpUrl(href) || !sameSite(baseUrl, href)) {
                continue;
            }
            if (!PAGE_TEXT_PATTERN.matcher(text + " " + href).find()) {
                continue;
            }
            String cleanUrl = stripFragment(href);
            if (!seen.add(cleanUrl)) {
                continue;
            }
            String type = text.contains("上一") || text.toLowerCase(Locale.ROOT).contains("prev") ? "previous" :
                text.contains("下一") || text.toLowerCase(Locale.ROOT).contains("next") ? "next" : "page";
            pages.add(Map.of("url", cleanUrl, "text", text, "type", type));
            if (pages.size() >= 20) {
                break;
            }
        }
        return pages;
    }

    private List<Map<String, Object>> recommendedActions(PageAnalysis analysis, String query) {
        List<Map<String, Object>> actions = new ArrayList<>();
        analysis.links.stream()
            .filter(link -> "detail".equals(link.get("type")) || "asset".equals(link.get("type")))
            .filter(link -> numberValue(link.get("score")) >= 0.45)
            .limit(5)
            .forEach(link -> actions.add(action("crawl_url", stringValue(link.get("url")), numberValue(link.get("score")), "Candidate detail or downloadable asset link is relevant to the query.")));
        if (actions.isEmpty() && ("detail".equals(analysis.pageType) || "asset".equals(analysis.pageType))) {
            actions.add(action("crawl_url", analysis.finalUrl, 0.72, "Current page looks like a detail/content page."));
        }
        analysis.links.stream()
            .filter(link -> "channel".equals(link.get("type")) || "search".equals(link.get("type")))
            .filter(link -> Boolean.TRUE.equals(link.get("sameSite")))
            .filter(link -> numberValue(link.get("score")) >= 0.25)
            .filter(link -> !stripFragment(stringValue(link.get("url"))).equals(stripFragment(analysis.finalUrl)))
            .limit(3)
            .forEach(link -> actions.add(action("web_page_analyze", stringValue(link.get("url")), numberValue(link.get("score")), "Analyze this internal section/search route before deciding whether to crawl detail pages.")));
        if (!analysis.searchForms.isEmpty()) {
            Map<String, Object> form = analysis.searchForms.get(0);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("action", "site_search");
            item.put("url", form.get("action"));
            item.put("params", Map.of(String.valueOf(form.get("inputName")), firstNonBlank(query, "")));
            item.put("confidence", form.get("confidence"));
            item.put("reason", "Search form found on the current page; use it when listed links do not directly answer the query.");
            actions.add(item);
        }
        analysis.pagination.stream()
            .filter(page -> "next".equals(page.get("type")))
            .findFirst()
            .ifPresent(page -> actions.add(action("web_page_analyze", stringValue(page.get("url")), 0.55, "Analyze the next page before deciding whether to crawl more results.")));
        if (actions.isEmpty()) {
            actions.add(action("crawl_url", analysis.finalUrl, 0.4, "No stronger route found; crawl current page only if the title or URL is relevant."));
        }
        return actions;
    }

    private Map<String, Object> action(String action, String url, double confidence, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("action", action);
        item.put("url", url);
        item.put("confidence", round(confidence));
        item.put("reason", reason);
        return item;
    }

    private String classifyPage(String url, Document document, PageAnalysis analysis) {
        String text = (url + " " + analysis.title).toLowerCase(Locale.ROOT);
        if (ASSET_PATH_PATTERN.matcher(text).find()) {
            return "asset";
        }
        if (SEARCH_TEXT_PATTERN.matcher(text).find()) {
            return "search";
        }
        if (DETAIL_PATH_PATTERN.matcher(url).find()) {
            return "detail";
        }
        long detailCount = analysis.links.stream().filter(link -> "detail".equals(link.get("type"))).count();
        if (!analysis.pagination.isEmpty() || detailCount >= 5) {
            return "list";
        }
        long channelCount = analysis.links.stream().filter(link -> "channel".equals(link.get("type"))).count();
        if (channelCount >= 5 || document.select("nav,header").size() > 0 || analysis.title.contains("门户")) {
            return "portal";
        }
        return "unknown";
    }

    private String linkType(String baseUrl, String href, String text) {
        String target = (href + " " + text).toLowerCase(Locale.ROOT);
        if (ASSET_PATH_PATTERN.matcher(target).find()) {
            return "asset";
        }
        if (!sameSite(baseUrl, href)) {
            return "external";
        }
        if (SEARCH_TEXT_PATTERN.matcher(target).find()) {
            return "search";
        }
        if (PAGE_TEXT_PATTERN.matcher(target).find()) {
            return "pagination";
        }
        if (DETAIL_PATH_PATTERN.matcher(target).find()) {
            return "detail";
        }
        return "channel";
    }

    private boolean shouldIgnoreLink(Element link,
                                     String baseUrl,
                                     String href,
                                     String text,
                                     String snippet,
                                     String type,
                                     String query) {
        String cleanUrl = stripFragment(href);
        String cleanBaseUrl = stripFragment(baseUrl);
        String normalizedText = cleanText(text);
        boolean relevantToQuery = queryScore(query, href + " " + normalizedText + " " + snippet) > 0;
        if (cleanUrl.equals(cleanBaseUrl) && (normalizedText.isBlank() || LOW_VALUE_LINK_TEXT_PATTERN.matcher(normalizedText).find())) {
            return true;
        }
        if (PROMOTIONAL_URL_PATTERN.matcher(href).find() && !relevantToQuery) {
            return true;
        }
        if (LOW_VALUE_LINK_TEXT_PATTERN.matcher(normalizedText).find() && !"search".equals(type) && !"asset".equals(type) && !relevantToQuery) {
            return true;
        }
        if (isNavigationOrUtilityLink(link) && ("channel".equals(type) || "external".equals(type)) && !relevantToQuery) {
            return true;
        }
        return false;
    }

    private boolean isNavigationOrUtilityLink(Element link) {
        Element current = link;
        for (int depth = 0; current != null && depth < 5; depth++, current = current.parent()) {
            String tag = current.tagName().toLowerCase(Locale.ROOT);
            if (Set.of("nav", "header", "footer").contains(tag)) {
                return true;
            }
            String marker = String.join(" ",
                current.id(),
                current.className(),
                current.attr("role"),
                current.attr("aria-label"),
                current.attr("data-spm"),
                current.attr("data-type")).toLowerCase(Locale.ROOT);
            if (marker.matches(".*(nav|menu|header|footer|toolbar|topbar|login|passport|client|download|app|ad|ads|advert|promotion|promo|quick|shortcut|utility).*")) {
                return true;
            }
            if (current.select("a[href]").size() >= 18 && cleanText(current.text()).length() > 120) {
                return true;
            }
        }
        return false;
    }

    private double linkScore(String baseUrl, String href, String text, String snippet, String type, String query) {
        double score = sameSite(baseUrl, href) ? 0.25 : 0.08;
        score += switch (type) {
            case "detail" -> 0.28;
            case "asset" -> 0.24;
            case "search" -> 0.18;
            case "pagination" -> 0.12;
            case "channel" -> 0.1;
            default -> 0.02;
        };
        score += queryScore(query, href + " " + text + " " + snippet);
        if (isProbablyIndexLikeUrl(href) && "channel".equals(type)) {
            score -= 0.08;
        }
        return Math.max(0, Math.min(0.99, score));
    }

    private boolean isProbablyIndexLikeUrl(String href) {
        try {
            URI uri = URI.create(stripFragment(href));
            String path = firstNonBlank(uri.getPath(), "/").toLowerCase(Locale.ROOT);
            return "/".equals(path) || path.endsWith("/index.html") || path.endsWith("/index.htm") || path.endsWith("/default.html");
        } catch (Exception ex) {
            return false;
        }
    }

    private String linkSnippet(Element link, String anchorText) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, anchorText);
        addCandidate(candidates, link.attr("title"));
        addCandidate(candidates, link.attr("aria-label"));
        addCandidate(candidates, link.attr("data-title"));
        addCandidate(candidates, link.attr("data-desc"));
        addCandidate(candidates, link.attr("data-description"));
        Element image = link.selectFirst("img[alt]");
        if (image != null) {
            addCandidate(candidates, image.attr("alt"));
        }

        Element context = bestLinkContext(link, anchorText);
        if (context != null) {
            addCandidate(candidates, context.text());
        }

        String joined = cleanText(String.join(" ", candidates));
        if (joined.isBlank()) {
            return "";
        }
        return shorten(joined, 260);
    }

    private Element bestLinkContext(Element link, String anchorText) {
        Element current = link.parent();
        String anchor = cleanText(anchorText);
        for (int depth = 0; current != null && depth < 5; depth++, current = current.parent()) {
            String tag = current.tagName().toLowerCase(Locale.ROOT);
            if (Set.of("html", "body", "main", "nav", "header", "footer", "script", "style").contains(tag)) {
                continue;
            }
            String text = cleanText(current.text());
            if (text.length() < 12 || text.equals(anchor)) {
                continue;
            }
            int linkCount = current.select("a[href]").size();
            if (linkCount > 10 && text.length() > 180) {
                continue;
            }
            if (text.length() <= 700) {
                return current;
            }
        }
        return null;
    }

    private void addCandidate(List<String> candidates, String value) {
        String text = cleanText(value);
        if (!text.isBlank() && candidates.stream().noneMatch(text::equals)) {
            candidates.add(text);
        }
    }

    private double queryScore(String query, String text) {
        if (query == null || query.isBlank() || text == null || text.isBlank()) {
            return 0;
        }
        String normalizedQuery = normalizeSearchText(query);
        String normalizedText = normalizeSearchText(text);
        if (normalizedQuery.length() >= 4 && normalizedText.contains(normalizedQuery)) {
            return 0.42;
        }
        double score = 0;
        int count = 0;
        for (String token : normalizedQuery.split("\\s+")) {
            if (token.length() < 2) {
                continue;
            }
            count++;
            if (normalizedText.contains(token)) {
                score += 0.12;
            }
        }
        return count == 0 ? 0 : Math.min(0.36, score);
    }

    private String linkReason(String type, double score, String query) {
        if (query != null && !query.isBlank() && score >= 0.6) {
            return "Text or URL appears relevant to the query.";
        }
        return switch (type) {
            case "detail" -> "Looks like an article/detail page.";
            case "asset" -> "Looks like a downloadable document.";
            case "search" -> "Looks like a site search route.";
            case "pagination" -> "Looks like a pagination route.";
            case "channel" -> "Looks like an internal section/channel.";
            default -> "External or weakly related link.";
        };
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://" + trimmed;
        }
        return trimmed;
    }

    private String attr(Document document, String selector, String attr) {
        Element element = document.selectFirst(selector);
        if (element == null) {
            return null;
        }
        String value = element.hasAttr(attr) ? element.attr(attr) : element.absUrl(attr);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String withQuery(String action, String inputName, String query) {
        if (action == null || action.isBlank()) {
            return "";
        }
        String separator = action.contains("?") ? "&" : "?";
        return action + separator + URLEncoder.encode(firstNonBlank(inputName, "q"), StandardCharsets.UTF_8)
            + "=" + URLEncoder.encode(firstNonBlank(query, ""), StandardCharsets.UTF_8);
    }

    private boolean sameSite(String left, String right) {
        String leftHost = hostOf(left);
        String rightHost = hostOf(right);
        if (leftHost == null || rightHost == null) {
            return false;
        }
        return leftHost.equalsIgnoreCase(rightHost)
            || rightHost.endsWith("." + leftHost)
            || leftHost.endsWith("." + rightHost)
            || rootDomain(leftHost).equals(rootDomain(rightHost));
    }

    private String rootDomain(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("www.")) {
            normalized = normalized.substring(4);
        }
        String[] parts = normalized.split("\\.");
        if (parts.length <= 2) {
            return normalized;
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private String stripFragment(String url) {
        int index = url == null ? -1 : url.indexOf('#');
        return index >= 0 ? url.substring(0, index) : url;
    }

    private String selectUserAgent() {
        return firstNonBlank(properties.getUserAgent(), "ChatChat-MCP-Page-Analyzer/1.0");
    }

    private String cleanText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String shorten(String text, int maxChars) {
        String cleaned = cleanText(text);
        if (maxChars <= 0 || cleaned.length() <= maxChars) {
            return cleaned;
        }
        return cleaned.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
    }

    private String normalizeSearchText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{Punct}\\s]+", " ")
            .trim();
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double numberValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static final class PageAnalysis {
        private String pageUrl;
        private String finalUrl;
        private int status;
        private String title;
        private String description;
        private String canonicalUrl;
        private String domain;
        private String language;
        private String pageType;
        private List<Map<String, Object>> searchForms = List.of();
        private List<Map<String, Object>> links = List.of();
        private List<Map<String, Object>> pagination = List.of();
        private List<Map<String, Object>> recommendedActions = List.of();

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("pageUrl", pageUrl);
            map.put("finalUrl", finalUrl);
            map.put("status", status);
            map.put("title", title);
            map.put("description", description);
            map.put("canonicalUrl", canonicalUrl);
            map.put("domain", domain);
            map.put("language", language);
            map.put("pageType", pageType);
            map.put("searchForms", searchForms);
            map.put("links", links);
            map.put("pagination", pagination);
            map.put("recommendedActions", recommendedActions);
            map.put("decisionHint", "Use this page map to choose crawl_url/web_crawler only for selected URLs; do not crawl the whole site by default.");
            return map;
        }
    }
}
