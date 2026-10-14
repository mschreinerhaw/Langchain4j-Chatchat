package com.chatchat.knowledgebase.search.query.application;

import com.chatchat.common.retrieval.query.QueryTermProtector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

/** Reuses the existing tokenizer/keyword extractor. Numbers are protected separately before ranking. */
@Component
@RequiredArgsConstructor
public class QueryKeywordVocabulary implements QueryTermProtector {
    private final KeywordExtractor extractor;
    @Override public List<String> protectedPhrases(String source, int maximumPhrases) { return List.of(); }
    @Override public List<String> keywordCandidates(String source, int maximumKeywords) {
        return extractor.extractKeywords(source, maximumKeywords);
    }
}
