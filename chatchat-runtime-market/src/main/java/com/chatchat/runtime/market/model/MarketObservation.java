package com.chatchat.runtime.market.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MarketObservation(MarketSource source, String title, String content, String summary,
                                String author, String sourceUrl, Instant publishTime, String language,
                                List<String> categories, List<String> tags, Map<String, Object> metadata) { }
