package com.chatchat.runtime.news.tool;

import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.news.model.NewsDocument;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NewsToolSupport {
    private NewsToolSupport() { }

    static int boundedInt(Number number, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, number == null ? fallback : number.intValue()));
    }

    static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    static List<Long> longs(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<Long> result = new ArrayList<>();
        values.forEach(item -> result.add(item instanceof Number n ? n.longValue() : Long.parseLong(item.toString())));
        return result;
    }

    static List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }

    static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "…";
    }

    static ToolOutput unavailable(String toolName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", false);
        data.put("code", "NEWS_STORE_UNAVAILABLE");
        data.put("message", "News资讯存储未启用或OpenSearch连接未配置");
        data.put("tool", toolName);
        data.put("capability", "news");
        return ToolOutput.builder().success(false).data(data)
            .errorMessage("NEWS_STORE_UNAVAILABLE: News资讯存储未启用或OpenSearch连接未配置").build();
    }

    static Map<String, Object> evidenceItem(NewsDocument document) {
        Map<String, Object> item = new LinkedHashMap<>();
        String evidenceTitle = metadataText(document, "evidenceTitle", document.title());
        String evidenceUrl = metadataText(document, "evidenceUrl", document.sourceUrl());
        item.put("documentId", document.documentId());
        item.put("title", document.title());
        item.put("url", document.sourceUrl());
        item.put("sourceUrl", document.sourceUrl());
        item.put("content", document.content());
        item.put("summary", document.summary());
        item.put("sourceId", document.sourceId());
        item.put("sourceName", document.sourceName());
        item.put("publishTime", document.publishTime());
        item.put("categories", document.categories());
        item.put("tags", document.tags());
        item.put("documentKind", document.metadata().getOrDefault("documentKind", "news_article"));
        item.put("parentDocumentId", document.metadata().get("parentDocumentId"));
        item.put("attachmentFileName", document.metadata().get("attachmentFileName"));
        item.put("chunkIndex", document.metadata().get("chunkIndex"));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("title", evidenceTitle);
        evidence.put("url", evidenceUrl);
        evidence.put("sourceName", document.sourceName());
        evidence.put("publishTime", document.publishTime());
        evidence.put("attachmentTitle", document.metadata().get("attachmentFileName"));
        evidence.put("attachmentUrl", document.metadata().get("attachmentUrl"));
        item.put("evidence", evidence);
        return item;
    }

    static List<String> evidenceUrls(List<Map<String, Object>> items) {
        return items.stream().flatMap(item -> {
            Object value = item.get("evidence");
            if (!(value instanceof Map<?, ?> evidence)) return java.util.stream.Stream.<String>empty();
            return java.util.stream.Stream.of(evidence.get("url"), evidence.get("attachmentUrl"))
                .filter(java.util.Objects::nonNull).map(String::valueOf).filter(url -> !url.isBlank());
        }).distinct().toList();
    }

    private static String metadataText(NewsDocument document, String key, String fallback) {
        Object value = document.metadata().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
