package com.chatchat.runtime.news.analysis;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight local analysis that remains available when no external LLM is configured. */
@Component
public class NewsContentAnalyzer {
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?；;])");
    private static final Pattern STOCK_CODE = Pattern.compile("(?<!\\d)(?:[036]\\d{5})(?!\\d)");
    private static final Map<String, List<String>> CATEGORY_KEYWORDS = categories();
    private final NewsRuntimeProperties.Analysis properties;

    public NewsContentAnalyzer(NewsRuntimeProperties runtimeProperties) {
        this.properties = runtimeProperties.getAnalysis();
    }

    public NewsDocument analyze(NewsDocument document) {
        String text = normalized(document.title() + "。" + document.content());
        LinkedHashSet<String> categories = new LinkedHashSet<>(safe(document.categories()));
        LinkedHashSet<String> tags = new LinkedHashSet<>(safe(document.tags()));
        for (Map.Entry<String, List<String>> entry : CATEGORY_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (text.contains(keyword)) {
                    categories.add(entry.getKey());
                    tags.add(keyword);
                    break;
                }
            }
        }
        Matcher codes = STOCK_CODE.matcher(text);
        while (codes.find() && tags.size() < maxTags()) tags.add(codes.group());
        List<String> limitedTags = new ArrayList<>(tags).stream().limit(maxTags()).toList();
        String summary = document.summary() == null || document.summary().isBlank()
            ? summarize(document.content()) : document.summary().trim();
        return new NewsDocument(document.documentId(), document.sourceId(), document.sourceName(), document.sourceType(),
            document.title(), document.content(), summary, document.author(), document.sourceUrl(), document.publishTime(),
            document.collectTime(), document.language(), List.copyOf(categories), limitedTags, document.contentHash(),
            NewsAnalysisStatus.COMPLETED, document.metadata());
    }

    private String summarize(String content) {
        String clean = normalized(content);
        int limit = Math.max(80, properties.getSummaryMaxChars());
        if (clean.length() <= limit) return clean;
        StringBuilder result = new StringBuilder();
        for (String sentence : SENTENCE_END.split(clean)) {
            if (result.length() > 0 && result.length() + sentence.length() > limit) break;
            result.append(sentence);
            if (result.length() >= limit * 2 / 3) break;
        }
        if (result.isEmpty()) return clean.substring(0, limit) + "…";
        return result.toString();
    }

    private int maxTags() {
        return Math.max(1, properties.getMaxTags());
    }

    private String normalized(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private List<String> safe(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    private static Map<String, List<String>> categories() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("上市公司", List.of("上市公司", "董事会", "股东", "业绩", "增持", "减持", "停牌", "复牌"));
        values.put("市场动态", List.of("A股", "指数", "涨停", "跌停", "成交额", "大盘", "行情"));
        values.put("宏观经济", List.of("央行", "货币政策", "GDP", "CPI", "财政政策", "利率"));
        values.put("监管", List.of("证监会", "交易所", "监管", "处罚", "问询函"));
        values.put("债券", List.of("债券", "可转债", "公司债", "票据"));
        values.put("基金", List.of("基金", "ETF", "公募", "私募"));
        values.put("衍生品", List.of("期货", "期权", "衍生品"));
        values.put("国际财经", List.of("美联储", "国际市场", "海外市场", "欧洲央行"));
        return values;
    }
}
