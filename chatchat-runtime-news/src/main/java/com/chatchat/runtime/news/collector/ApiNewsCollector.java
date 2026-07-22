package com.chatchat.runtime.news.collector;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.chatchat.runtime.news.normalize.PublishTimeParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ApiNewsCollector implements NewsCollector {

    private final NewsItemSink sink;
    private final NewsRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final PublishTimeParser publishTimeParser;
    private final InternalCredentialProperties credentials;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public ApiNewsCollector(NewsItemSink sink, NewsRuntimeProperties properties,
                            ObjectMapper objectMapper, PublishTimeParser publishTimeParser,
                            InternalCredentialProperties credentials) {
        this.sink = sink;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.publishTimeParser = publishTimeParser;
        this.credentials = credentials;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.API;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(source.entryUrl()))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                .header("User-Agent", "ChatChat-NewsCollector/1.0").GET();
            Object headers = source.configuration().get("headers");
            if (headers instanceof Map<?, ?> values) {
                values.forEach((key, value) -> builder.header(String.valueOf(key), String.valueOf(value)));
            }
            Object encryptedHeaders = source.configuration().get("encryptedHeaders");
            if (encryptedHeaders instanceof Map<?, ?> values) values.forEach((key, value) ->
                builder.header(String.valueOf(key), credentials.resolveSecret(String.valueOf(value), null)));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("News API returned HTTP " + response.statusCode());
            }
            JsonNode items = at(objectMapper.readTree(response.body()), config(source, "itemsPath", "items"));
            if (!items.isArray()) {
                throw new IllegalArgumentException("Configured itemsPath does not point to a JSON array");
            }
            for (JsonNode item : items) {
                if (counters.discovered >= properties.getMaxItemsPerRun()) break;
                counters.discovered++;
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("transport", "api");
                String datasetCode = config(source, "datasetCode", "");
                if (!datasetCode.isBlank()) {
                    metadata.putAll(objectMapper.convertValue(item, Map.class));
                    metadata.put("datasetCode", datasetCode);
                    metadata.put("datasetName", config(source, "datasetName", datasetCode));
                    metadata.put("businessDescription", config(source, "businessDescription", ""));
                }
                metadata.entrySet().removeIf(entry -> entry.getValue() == null);
                RawNewsItem raw = new RawNewsItem(source,
                    text(item, config(source, "titleField", "title")),
                    text(item, config(source, "contentField", "content")),
                    text(item, config(source, "summaryField", "summary")),
                    text(item, config(source, "authorField", "author")),
                    text(item, config(source, "urlField", "url")),
                    publishTimeParser.parse(text(item, config(source, "publishTimeField", "publishTime")),
                        config(source, "zoneId", "Asia/Shanghai")),
                    text(item, config(source, "languageField", "language")),
                    strings(at(item, config(source, "categoriesField", "categories"))),
                    strings(at(item, config(source, "tagsField", "tags"))), Map.copyOf(metadata));
                counters.add(sink.accept(raw));
            }
            return counters.result(context, source, null);
        } catch (Exception ex) {
            return counters.result(context, source, ex.getMessage());
        }
    }

    private String config(NewsSource source, String name, String fallback) {
        Object value = source.configuration().get(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private JsonNode at(JsonNode node, String dottedPath) {
        JsonNode current = node;
        for (String part : dottedPath.split("\\.")) current = current.path(part);
        return current;
    }

    private String text(JsonNode item, String path) {
        JsonNode value = at(item, path);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private List<String> strings(JsonNode node) {
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(value -> values.add(value.asText()));
            return values;
        }
        return node.isTextual() && !node.asText().isBlank() ? List.of(node.asText()) : List.of();
    }

    private static class Counters {
        int discovered;
        int accepted;
        int duplicate;
        int rejected;

        void add(NewsAcceptance acceptance) {
            if (acceptance == NewsAcceptance.ACCEPTED) accepted++;
            else if (acceptance == NewsAcceptance.DUPLICATE) duplicate++;
            else rejected++;
        }

        NewsCollectResult result(NewsCollectContext context, NewsSource source, String error) {
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, error == null ? 0 : 1, error);
        }
    }
}
