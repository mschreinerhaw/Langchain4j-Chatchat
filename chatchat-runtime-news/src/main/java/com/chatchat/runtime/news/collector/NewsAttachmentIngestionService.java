package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.store.NewsBulkIndexer;
import jakarta.annotation.PreDestroy;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Bounded, asynchronous attachment download and extraction pipeline. */
@Service
public class NewsAttachmentIngestionService {
    private static final Logger log = LoggerFactory.getLogger(NewsAttachmentIngestionService.class);
    private static final Set<String> EXTENSIONS = Set.of("pdf", "doc", "docx", "xls", "xlsx", "csv");
    private static final Pattern DISPOSITION_FILE_NAME = Pattern.compile(
        "(?i)filename\\*?=(?:UTF-8''|\\\")?([^\\\";]+)");

    private final NewsRuntimeProperties.Attachment properties;
    private final NewsBulkIndexer bulkIndexer;
    private final HttpClient httpClient;
    private final ThreadPoolExecutor executor;

    @Autowired
    public NewsAttachmentIngestionService(NewsRuntimeProperties runtimeProperties, NewsBulkIndexer bulkIndexer) {
        this(runtimeProperties.getAttachment(), bulkIndexer, HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    NewsAttachmentIngestionService(NewsRuntimeProperties.Attachment properties, NewsBulkIndexer bulkIndexer,
                                   HttpClient httpClient) {
        this.properties = properties;
        this.bulkIndexer = bulkIndexer;
        this.httpClient = httpClient;
        int workers = Math.max(1, properties.getWorkerCount());
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity())), runnable -> {
                Thread thread = new Thread(runnable, "runtime-news-attachment");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    public void submit(NewsDocument parent) {
        if (!properties.isEnabled() || parent == null) return;
        List<String> urls = strings(parent.metadata().get("attachmentUrls"));
        Set<String> allowedDomains = new LinkedHashSet<>(strings(parent.metadata().get("attachmentAllowedDomains")));
        try { allowedDomains.add(URI.create(parent.sourceUrl()).getHost()); } catch (Exception ignored) { }
        int limit = Math.min(urls.size(), Math.max(0, properties.getMaxAttachmentsPerArticle()));
        for (int i = 0; i < limit; i++) {
            String url = urls.get(i);
            if (!allowed(url, allowedDomains)) continue;
            try {
                executor.execute(() -> process(parent, url, allowedDomains));
            } catch (RuntimeException ex) {
                log.warn("News attachment queue is full parent={} url={}", parent.documentId(), url);
                break;
            }
        }
    }

    private void process(NewsDocument parent, String url, Set<String> allowedDomains) {
        long startedAt = System.currentTimeMillis();
        try {
            log.info("news_attachment_started parentDocumentId={} sourceId={} url={}", parent.documentId(), parent.sourceId(), url);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1, properties.getRequestTimeoutMillis())))
                .header("User-Agent", "ChatChat-NewsCollector/1.0")
                .header("Accept", "application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv")
                .GET().build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                || !allowed(response.uri().toString(), allowedDomains)
                || !supportedResponse(response)) {
                close(response.body());
                log.warn("Rejected news attachment response status={} url={}", response.statusCode(), url);
                return;
            }
            byte[] bytes;
            try (InputStream input = response.body()) { bytes = readLimited(input, properties.getMaxFileBytes()); }
            String fileName = fileName(response);
            String text = extract(bytes, fileName);
            List<String> chunks = chunks(text);
            int queuedChunks = 0;
            for (int index = 0; index < chunks.size(); index++) {
                NewsDocument document = chunkDocument(parent, response.uri().toString(), fileName, chunks.get(index), index, chunks.size());
                if (!bulkIndexer.submit(document)) {
                    log.warn("News Bulk queue is full while indexing attachment parent={} url={}", parent.documentId(), url);
                    break;
                }
                queuedChunks++;
            }
            log.info("news_attachment_completed parentDocumentId={} sourceId={} fileName={} bytes={} extractedChars={} chunks={} queuedChunks={} durationMs={}",
                parent.documentId(), parent.sourceId(), fileName, bytes.length, text.length(), chunks.size(), queuedChunks,
                System.currentTimeMillis() - startedAt);
        } catch (Exception ex) {
            log.warn("news_attachment_failed parentDocumentId={} sourceId={} url={} durationMs={} error={}",
                parent.documentId(), parent.sourceId(), url, System.currentTimeMillis() - startedAt, ex.getMessage());
        }
    }

    private String extract(byte[] bytes, String fileName) throws Exception {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        BodyContentHandler handler = new BodyContentHandler(Math.max(1, properties.getMaxExtractedChars()));
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            new AutoDetectParser().parse(input, handler, metadata, new ParseContext());
        }
        return handler.toString().replace('\u0000', ' ').replaceAll("[\\t ]+", " ")
            .replaceAll("\\R{3,}", "\n\n").trim();
    }

    private List<String> chunks(String text) {
        if (text == null || text.isBlank()) return List.of();
        int size = Math.max(200, properties.getChunkSize());
        int overlap = Math.max(0, Math.min(properties.getChunkOverlap(), size / 2));
        int max = Math.max(1, properties.getMaxChunksPerAttachment());
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length() && chunks.size() < max;) {
            int end = Math.min(text.length(), start + size);
            if (end < text.length()) {
                int boundary = Math.max(text.lastIndexOf('\n', end), Math.max(text.lastIndexOf('。', end), text.lastIndexOf(';', end)));
                if (boundary > start + size / 2) end = boundary + 1;
            }
            String value = text.substring(start, end).trim();
            if (!value.isBlank()) chunks.add(value);
            if (end >= text.length()) break;
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    private NewsDocument chunkDocument(NewsDocument parent, String url, String fileName, String content,
                                       int index, int total) {
        String attachmentId = sha256(url);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentKind", "attachment_chunk");
        metadata.put("parentDocumentId", parent.documentId());
        metadata.put("parentTitle", parent.title());
        metadata.put("parentUrl", parent.sourceUrl());
        metadata.put("evidenceTitle", parent.title());
        metadata.put("evidenceUrl", parent.sourceUrl());
        metadata.put("attachmentUrl", url);
        metadata.put("attachmentFileName", fileName);
        metadata.put("chunkIndex", index);
        metadata.put("chunkCount", total);
        return new NewsDocument(parent.documentId() + "_att_" + attachmentId.substring(0, 16) + "_" + index,
            parent.sourceId(), parent.sourceName(), parent.sourceType(), parent.title() + " / " + fileName,
            content, null, parent.author(), url, parent.publishTime(), parent.collectTime(), parent.language(),
            parent.categories(), parent.tags(), sha256(content), NewsAnalysisStatus.PENDING, Map.copyOf(metadata));
    }

    private byte[] readLimited(InputStream input, long configuredLimit) throws Exception {
        long limit = Math.max(1, configuredLimit);
        byte[] bytes = input.readNBytes((int) Math.min(Integer.MAX_VALUE, limit + 1));
        if (bytes.length > limit) throw new IllegalArgumentException("Attachment exceeds maxFileBytes=" + limit);
        return bytes;
    }

    private boolean allowed(String value, Set<String> domains) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return domains.stream().filter(domain -> domain != null && !domain.isBlank()).map(domain -> domain.toLowerCase(Locale.ROOT))
                .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        } catch (Exception ex) { return false; }
    }

    private boolean supportedUrl(String value) {
        try {
            URI uri = URI.create(value);
            String candidate = (uri.getPath() + "?" + (uri.getQuery() == null ? "" : uri.getQuery())).toLowerCase(Locale.ROOT);
            return supportedName(candidate);
        } catch (Exception ex) { return false; }
    }

    private boolean supportedName(String value) {
        String candidate = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(extension ->
            candidate.matches(".*\\." + extension + "(?:$|[?&=;\\\"'].*)"));
    }

    private boolean supportedResponse(HttpResponse<?> response) {
        if (supportedUrl(response.uri().toString())) return true;
        String contentType = response.headers().firstValue("Content-Type").orElse("")
            .toLowerCase(Locale.ROOT);
        if (contentType.contains("application/pdf") || contentType.contains("application/msword")
            || contentType.contains("wordprocessingml") || contentType.contains("ms-excel")
            || contentType.contains("spreadsheetml") || contentType.contains("text/csv")) return true;
        return response.headers().firstValue("Content-Disposition")
            .map(this::supportedName).orElse(false);
    }

    private String fileName(URI uri) {
        String path = uri.getPath();
        String value = path == null || path.isBlank() ? "attachment" : path.substring(path.lastIndexOf('/') + 1);
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String fileName(HttpResponse<?> response) {
        String disposition = response.headers().firstValue("Content-Disposition").orElse("");
        var matcher = DISPOSITION_FILE_NAME.matcher(disposition);
        if (matcher.find()) return URLDecoder.decode(matcher.group(1).trim(), StandardCharsets.UTF_8);
        return fileName(response.uri());
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(item -> { if (item != null && !item.toString().isBlank()) result.add(item.toString()); });
        return result.stream().distinct().toList();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private void close(InputStream input) { try { input.close(); } catch (Exception ignored) { } }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
