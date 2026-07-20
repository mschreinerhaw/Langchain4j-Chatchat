package com.chatchat.runtime.news.normalize;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.RawNewsItem;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class NewsNormalizer {

    private final NewsRuntimeProperties properties;

    public NewsNormalizer(NewsRuntimeProperties properties) {
        this.properties = properties;
    }

    public NewsDocument normalize(RawNewsItem raw) {
        String title = clean(raw.title());
        String content = clean(raw.content());
        String sourceUrl = normalizeUrl(raw.sourceUrl());
        if (title.isBlank()) {
            throw new IllegalArgumentException("News title is blank");
        }
        boolean hasAttachments = raw.metadata() != null
            && raw.metadata().get("attachmentUrls") instanceof Iterable<?> attachments
            && attachments.iterator().hasNext();
        int minimumContentChars = sourceMinimumContentChars(raw);
        if (content.length() < minimumContentChars && !hasAttachments) {
            throw new IllegalArgumentException("News content is shorter than minimumContentChars");
        }
        if (content.isBlank() && hasAttachments) content = title;
        String urlHash = sha256(sourceUrl);
        String contentHash = sha256(content);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (raw.metadata() != null) metadata.putAll(raw.metadata());
        // Explicit evidence identity is persisted even though title/sourceUrl also remain top-level fields.
        metadata.put("evidenceTitle", title);
        metadata.put("evidenceUrl", sourceUrl);
        return new NewsDocument(
            "news_" + urlHash,
            raw.source().id(), raw.source().name(), raw.source().sourceType(),
            title, content, cleanNullable(raw.summary()), cleanNullable(raw.author()), sourceUrl,
            raw.publishTime(), Instant.now(), defaultString(raw.language(), "und"),
            safeList(raw.categories()), safeList(raw.tags()), contentHash,
            NewsAnalysisStatus.PENDING, Map.copyOf(metadata)
        );
    }

    public String urlHash(String sourceUrl) {
        return sha256(normalizeUrl(sourceUrl));
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return Jsoup.parse(value).text().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private String cleanNullable(String value) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? null : cleaned;
    }

    private String normalizeUrl(String value) {
        URI uri = URI.create(value == null ? "" : value.trim()).normalize();
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("News sourceUrl must be an absolute HTTP(S) URL");
        }
        try {
            return new URI(uri.getScheme().toLowerCase(), uri.getUserInfo(), uri.getHost().toLowerCase(),
                uri.getPort(), uri.getPath(), uri.getQuery(), null).toString();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid news sourceUrl", ex);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).distinct().toList();
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int sourceMinimumContentChars(RawNewsItem raw) {
        if (raw.source() == null || raw.source().configuration() == null) {
            return properties.getMinimumContentChars();
        }
        Object configured = raw.source().configuration().get("minimumContentChars");
        return configured instanceof Number number
            ? Math.max(1, number.intValue()) : properties.getMinimumContentChars();
    }
}
