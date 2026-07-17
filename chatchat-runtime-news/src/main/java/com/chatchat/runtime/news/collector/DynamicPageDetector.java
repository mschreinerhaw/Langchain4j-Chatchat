package com.chatchat.runtime.news.collector;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Diagnoses client-rendered HTML shells after an expected content selector returns no items. */
@Component
public class DynamicPageDetector {
    private static final Pattern TEMPLATE_EXPRESSION = Pattern.compile("\\{\\{[^{}]{1,200}}}");

    public Detection inspect(String html) {
        String source = html == null ? "" : html;
        String lower = source.toLowerCase(Locale.ROOT);
        Document document = Jsoup.parse(source);
        List<String> reasons = new ArrayList<>();
        if (TEMPLATE_EXPRESSION.matcher(source).find()) reasons.add("unrendered-template-expression");
        if (lower.contains(" v-if=") || lower.contains(" v-for=") || lower.contains(" v-model=")
            || lower.contains("data-v-")) reasons.add("vue-template-markers");
        if (lower.contains("__next_data__") || lower.contains("_next/static/")) reasons.add("nextjs-markers");
        if (lower.contains("webpackjsonp") || lower.contains("webpackchunk") || lower.contains("__webpack_require__"))
            reasons.add("webpack-runtime");
        if (document.select("#app:empty, #root:empty, #__next:empty").size() > 0) reasons.add("empty-app-root");
        if ((lower.contains("axios") || lower.contains("xmlhttprequest") || lower.contains("/api/"))
            && document.body().text().length() < 500) reasons.add("api-driven-empty-shell");
        return new Detection(!reasons.isEmpty(), List.copyOf(reasons), document.body().text().length(),
            document.select("script").size());
    }

    public String selectorFailure(String html, String selector) {
        Detection detection = inspect(html);
        if (detection.dynamic()) {
            return "Dynamic page detected after selector matched 0 items; selector=" + selector
                + ", indicators=" + String.join(",", detection.indicators())
                + ". Configure an official JSON API or a dedicated source collector.";
        }
        return "Configured selector matched 0 items; selector=" + selector
            + ". The site structure or selector may have changed.";
    }

    public record Detection(boolean dynamic, List<String> indicators, int visibleTextLength, int scriptCount) { }
}
