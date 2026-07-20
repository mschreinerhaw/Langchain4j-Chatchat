package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.chatchat.runtime.news.normalize.PublishTimeParser;
import org.springframework.stereotype.Component;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.processor.PageProcessor;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Precise news-page extraction. This class deliberately has no generic link-recursion mode. */
@Component
public class WebMagicNewsCollector implements NewsCollector {

    private static final String PAGE_TYPE = "newsPageType";
    private static final String LIST = "list";
    private static final String DETAIL = "detail";

    private final NewsItemSink sink;
    private final NewsRuntimeProperties properties;
    private final PublishTimeParser publishTimeParser;
    private final DynamicPageDetector dynamicPageDetector;

    public WebMagicNewsCollector(NewsItemSink sink, NewsRuntimeProperties properties,
                                 PublishTimeParser publishTimeParser, DynamicPageDetector dynamicPageDetector) {
        this.sink = sink;
        this.properties = properties;
        this.publishTimeParser = publishTimeParser;
        this.dynamicPageDetector = dynamicPageDetector;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.WEB_LIST || sourceType == NewsSourceType.WEB_SINGLE_PAGE;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        try {
            Request seed = new Request(source.entryUrl());
            seed.putExtra(PAGE_TYPE, source.sourceType() == NewsSourceType.WEB_LIST ? LIST : DETAIL);
            Spider.create(new PreciseNewsProcessor(source, counters))
                .addRequest(seed).thread(1).run();
            return counters.result(context, source, null);
        } catch (Exception ex) {
            return counters.result(context, source, ex.getMessage());
        }
    }

    private class PreciseNewsProcessor implements PageProcessor {
        private final NewsSource source;
        private final Counters counters;
        private final Site site;
        private final Pattern urlPattern;

        private PreciseNewsProcessor(NewsSource source, Counters counters) {
            this.source = source;
            this.counters = counters;
            this.site = Site.me().setRetryTimes(2).setSleepTime(intConfig(source, "sleepMillis", 250))
                .setTimeOut(intConfig(source, "timeoutMillis", 15_000))
                .setUserAgent("ChatChat-NewsCollector/1.0");
            String pattern = source.selectors().get("urlPattern");
            this.urlPattern = pattern == null ? null : Pattern.compile(pattern);
        }

        @Override
        public void process(Page page) {
            Object pageType = page.getRequest().getExtra(PAGE_TYPE);
            String type = pageType == null ? "" : pageType.toString();
            if (LIST.equals(type)) {
                discoverConfiguredDetailLinks(page);
                page.setSkip(true);
                return;
            }
            if (counters.discovered.incrementAndGet() > properties.getMaxItemsPerRun()) {
                page.setSkip(true);
                return;
            }
            String title = page.getHtml().css(selector("titleSelector")).get();
            String content = page.getHtml().css(selector("contentSelector")).get();
            String author = optionalCss(page, "authorSelector");
            String published = optionalCss(page, "publishTimeSelector");
            List<String> attachments = attachmentLinks(page);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("transport", "webmagic");
            if (!attachments.isEmpty()) {
                metadata.put("attachmentUrls", attachments);
                metadata.put("attachmentAllowedDomains", attachmentAllowedDomains());
            }
            String category = stringConfig(source, "category", null);
            NewsAcceptance acceptance = sink.accept(new RawNewsItem(source, title, content, null, author,
                page.getUrl().get(), publishTimeParser.parse(published, stringConfig(source, "zoneId", "Asia/Shanghai")),
                stringConfig(source, "language", null), category == null || category.isBlank() ? List.of() : List.of(category),
                List.of(), Map.copyOf(metadata)));
            counters.add(acceptance);
        }

        private List<String> attachmentLinks(Page page) {
            String configuredSelector = stringConfig(source, "attachmentSelector", null);
            List<String> links = configuredSelector == null || configuredSelector.isBlank()
                ? page.getHtml().links().all() : page.getHtml().css(configuredSelector).links().all();
            int limit = Math.max(0, properties.getAttachment().getMaxAttachmentsPerArticle());
            return links.stream().filter(this::isAllowedAttachmentUrl).distinct().limit(limit).toList();
        }

        private boolean isAllowedAttachmentUrl(String value) {
            try {
                URI uri = URI.create(value);
                if (uri.getHost() == null || uri.getPath() == null) return false;
                String path = (uri.getPath() + "?" + (uri.getQuery() == null ? "" : uri.getQuery())).toLowerCase();
                boolean supported = path.matches(".*\\.(pdf|doc|docx|xls|xlsx|csv)(?:$|[?&=].*)");
                if (!supported) return false;
                String host = uri.getHost().toLowerCase();
                return attachmentAllowedDomains().stream().map(String::toLowerCase)
                    .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
            } catch (Exception ex) { return false; }
        }

        private List<String> attachmentAllowedDomains() {
            Set<String> domains = new LinkedHashSet<>();
            if (source.allowedDomain() != null && !source.allowedDomain().isBlank()) domains.add(source.allowedDomain());
            Object configured = source.configuration().get("attachmentAllowedDomains");
            if (configured instanceof Iterable<?> values) {
                values.forEach(value -> { if (value != null && !value.toString().isBlank()) domains.add(value.toString().trim()); });
            } else if (configured != null) {
                for (String value : configured.toString().split(",")) if (!value.isBlank()) domains.add(value.trim());
            }
            return new ArrayList<>(domains);
        }

        private void discoverConfiguredDetailLinks(Page page) {
            // Only anchors under the explicitly configured selector are considered. Detail pages add no links.
            String linkSelector = selector("linkSelector");
            org.jsoup.nodes.Document listDocument = org.jsoup.Jsoup.parse(page.getRawText(), page.getUrl().get());
            Set<String> directAttachments = new LinkedHashSet<>();
            for (org.jsoup.nodes.Element anchor : listDocument.select(linkSelector)) {
                String url = anchor.absUrl("href");
                if (!isAllowedAttachmentUrl(url) || directAttachments.size() >= properties.getMaxItemsPerRun()) continue;
                directAttachments.add(url);
                String title = anchor.text().trim();
                if (title.isBlank()) title = anchor.attr("title").trim();
                if (title.isBlank()) title = url.substring(url.lastIndexOf('/') + 1);
                org.jsoup.nodes.Element context = anchor.closest("tr, li, article, .item, .row");
                String content = context == null ? title : context.text();
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("transport", "webmagic-direct-attachment");
                metadata.put("attachmentUrls", List.of(url));
                metadata.put("attachmentAllowedDomains", attachmentAllowedDomains());
                counters.discovered.incrementAndGet();
                counters.add(sink.accept(new RawNewsItem(source, title, content, null, null, url, null,
                    stringConfig(source, "language", null), List.of(), List.of(), Map.copyOf(metadata))));
            }
            List<String> selected = page.getHtml().css(linkSelector).links().all();
            Set<String> accepted = new LinkedHashSet<>();
            for (String url : selected) {
                if (accepted.size() >= properties.getMaxItemsPerRun()) break;
                if (!directAttachments.contains(url) && isAllowedDetailUrl(url)) accepted.add(url);
            }
            for (String url : accepted) {
                Request detail = new Request(url);
                detail.putExtra(PAGE_TYPE, DETAIL);
                page.addTargetRequest(detail);
            }
            if (directAttachments.isEmpty() && accepted.isEmpty()) {
                counters.fail(dynamicPageDetector.selectorFailure(page.getRawText(), linkSelector));
            }
        }

        private boolean isAllowedDetailUrl(String value) {
            try {
                URI uri = URI.create(value);
                String expected = source.allowedDomain();
                boolean domain = expected != null && (uri.getHost().equalsIgnoreCase(expected)
                    || uri.getHost().toLowerCase().endsWith("." + expected.toLowerCase()));
                return domain && (urlPattern == null || urlPattern.matcher(value).matches());
            } catch (Exception ex) {
                return false;
            }
        }

        private String optionalCss(Page page, String key) {
            String css = source.selectors().get(key);
            return css == null ? null : page.getHtml().css(css).get();
        }

        private String selector(String key) {
            return source.selectors().get(key);
        }

        @Override
        public Site getSite() {
            return site;
        }
    }

    private int intConfig(NewsSource source, String key, int fallback) {
        Object value = source.configuration().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private String stringConfig(NewsSource source, String key, String fallback) {
        Object value = source.configuration().get(key);
        return value == null ? fallback : value.toString();
    }

    private static class Counters {
        final AtomicInteger discovered = new AtomicInteger();
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger duplicate = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicReference<String> error = new AtomicReference<>();

        void add(NewsAcceptance acceptance) {
            if (acceptance == NewsAcceptance.ACCEPTED) accepted.incrementAndGet();
            else if (acceptance == NewsAcceptance.DUPLICATE) duplicate.incrementAndGet();
            else rejected.incrementAndGet();
        }

        void fail(String message) {
            error.compareAndSet(null, message);
        }

        NewsCollectResult result(NewsCollectContext context, NewsSource source, String error) {
            String effectiveError = error == null ? this.error.get() : error;
            return new NewsCollectResult(context.executionId(), source.id(), discovered.get(), accepted.get(),
                duplicate.get(), rejected.get(), effectiveError == null ? 0 : 1, effectiveError);
        }
    }
}
