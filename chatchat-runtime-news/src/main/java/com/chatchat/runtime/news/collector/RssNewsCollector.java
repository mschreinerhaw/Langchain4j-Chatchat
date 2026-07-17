package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class RssNewsCollector implements NewsCollector {

    private final NewsItemSink sink;
    private final NewsRuntimeProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public RssNewsCollector(NewsItemSink sink, NewsRuntimeProperties properties) {
        this.sink = sink;
        this.properties = properties;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.RSS;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        int discovered = 0;
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(source.entryUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "ChatChat-NewsCollector/1.0")
                .GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("RSS request returned HTTP " + response.statusCode());
            }
            SyndFeed feed = new SyndFeedInput().build(new XmlReader(new ByteArrayInputStream(response.body())));
            for (SyndEntry entry : feed.getEntries()) {
                if (discovered >= properties.getMaxItemsPerRun()) {
                    break;
                }
                discovered++;
                String content = content(entry);
                NewsAcceptance outcome = sink.accept(new RawNewsItem(
                    source, entry.getTitle(), content, description(entry), entry.getAuthor(), entry.getLink(),
                    publishedAt(entry), feed.getLanguage(), List.of(), List.of(), Map.of("feedTitle", feed.getTitle())
                ));
                if (outcome == NewsAcceptance.ACCEPTED) accepted++;
                else if (outcome == NewsAcceptance.DUPLICATE) duplicate++;
                else rejected++;
            }
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate, rejected, 0, null);
        } catch (Exception ex) {
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate, rejected, 1, ex.getMessage());
        }
    }

    private String content(SyndEntry entry) {
        if (entry.getContents() != null) {
            for (SyndContent content : entry.getContents()) {
                if (content != null && content.getValue() != null && !content.getValue().isBlank()) {
                    return content.getValue();
                }
            }
        }
        return description(entry);
    }

    private String description(SyndEntry entry) {
        return entry.getDescription() == null ? null : entry.getDescription().getValue();
    }

    private Instant publishedAt(SyndEntry entry) {
        if (entry.getPublishedDate() != null) return entry.getPublishedDate().toInstant();
        return entry.getUpdatedDate() == null ? null : entry.getUpdatedDate().toInstant();
    }
}
