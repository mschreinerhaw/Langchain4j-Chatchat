package com.chatchat.knowledgebase.search;

import org.apache.lucene.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class ChunkReranker {

    public float score(float bm25Score,
                       Document hit,
                       String query,
                       List<String> terms,
                       QueryIntent intent) {
        float score = bm25Score;
        String phrase = normalize(query);
        String title = normalize(hit.get("title"));
        String section = normalize(hit.get("section"));
        String keywords = normalize(hit.get("keywords"));
        String content = normalize(firstPresent(hit.get("content"), hit.get("chunkText")));
        String chunkType = normalize(hit.get("chunkType"));
        float positionRatio = floatValue(hit.get("positionRatio"), 1.0F);

        score += phraseBoost(phrase, title, section, keywords, content);
        score += termBoost(terms, title, section, keywords, content);
        score += chunkTypeBoost(intent, chunkType);
        score += positionBoost(positionRatio, title, section, keywords, content, phrase, terms);
        return score;
    }

    private float phraseBoost(String phrase, String title, String section, String keywords, String content) {
        if (phrase.isBlank()) {
            return 0.0F;
        }
        float score = 0.0F;
        if (title.contains(phrase)) {
            score += 6.0F;
        }
        if (section.contains(phrase)) {
            score += 4.5F;
        }
        if (keywords.contains(phrase)) {
            score += 3.2F;
        }
        if (content.startsWith(phrase)) {
            score += 3.0F;
        } else if (content.contains(phrase)) {
            score += 1.8F;
        }
        return score;
    }

    private float termBoost(List<String> terms, String title, String section, String keywords, String content) {
        if (terms == null || terms.isEmpty()) {
            return 0.0F;
        }
        float score = 0.0F;
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            if (normalizedTerm.isBlank()) {
                continue;
            }
            if (title.contains(normalizedTerm)) {
                score += 1.4F;
            }
            if (section.contains(normalizedTerm)) {
                score += 1.2F;
            }
            if (keywords.contains(normalizedTerm)) {
                score += 1.0F;
            }
            if (content.contains(normalizedTerm)) {
                score += 0.35F;
            }
        }
        return score;
    }

    private float chunkTypeBoost(QueryIntent intent, String chunkType) {
        if (intent == null || chunkType.isBlank()) {
            return 0.0F;
        }
        return switch (intent) {
            case TROUBLESHOOTING -> matches(chunkType, "troubleshooting", "log") ? 3.0F : 0.0F;
            case HOW_TO -> matches(chunkType, "step", "example") ? 2.6F : 0.0F;
            case DATA_ISSUE -> matches(chunkType, "table", "troubleshooting") ? 2.2F : 0.0F;
            case POLICY -> matches(chunkType, "policy", "definition") ? 2.4F : 0.0F;
            case FAQ -> matches(chunkType, "definition", "example") ? 1.5F : 0.0F;
            case GENERAL -> 0.0F;
        };
    }

    private float positionBoost(float positionRatio,
                                String title,
                                String section,
                                String keywords,
                                String content,
                                String phrase,
                                List<String> terms) {
        boolean matched = !phrase.isBlank() && (title.contains(phrase)
            || section.contains(phrase)
            || keywords.contains(phrase)
            || content.contains(phrase));
        if (!matched && terms != null) {
            for (String term : terms) {
                String normalizedTerm = normalize(term);
                if (!normalizedTerm.isBlank()
                    && (title.contains(normalizedTerm)
                    || section.contains(normalizedTerm)
                    || keywords.contains(normalizedTerm)
                    || content.contains(normalizedTerm))) {
                    matched = true;
                    break;
                }
            }
        }
        if (!matched) {
            return 0.0F;
        }
        if (positionRatio <= 0.2F) {
            return 1.5F;
        }
        if (positionRatio <= 0.5F) {
            return 0.7F;
        }
        return 0.0F;
    }

    private boolean matches(String value, String... options) {
        for (String option : options) {
            if (value.equals(option)) {
                return true;
            }
        }
        return false;
    }

    private String firstPresent(String first, String second) {
        return first == null ? second : first;
    }

    private float floatValue(String value, float fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
