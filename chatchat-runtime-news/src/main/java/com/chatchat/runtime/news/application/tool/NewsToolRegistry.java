package com.chatchat.runtime.news.application.tool;

import com.chatchat.runtime.news.tool.NewsToolExecutor;

import java.util.Optional;

/** Read-only lookup boundary for tools executable by the internal API. */
public interface NewsToolRegistry {
    Optional<NewsToolExecutor> findExecutor(String toolName);
}
