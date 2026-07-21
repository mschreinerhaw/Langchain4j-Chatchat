package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsSource;

import java.util.Map;

/** Whitelisted request signing extension for declarative flash-news templates. */
public interface StructuredFlashRequestSigner {
    String name();

    void sign(Map<String, String> query, NewsSource source);

    default void signHeaders(Map<String, String> headers, NewsSource source) {
    }
}
