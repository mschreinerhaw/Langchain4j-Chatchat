package com.chatchat.knowledgebase.search.query;

import com.chatchat.knowledgebase.search.model.SearchDocument;

import com.chatchat.knowledgebase.search.rule.RetrievalRuleService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalKeywordFrequencyTest {

    @Test
    void derivesKeywordFrequencyFromActiveMaintainedRules() {
        RetrievalRuleService.RuleSnapshot snapshot = snapshot(
            List.of(
                intent("market", List.of("trade date", "index"), null),
                intent("lookup", List.of("index", "security"), null)
            ),
            List.of(chunk("market", List.of("index", "quote"), null)),
            List.of(new RetrievalRuleService.ExpandRule(
                "", "security", List.of("stock", "index"), 1, 0
            )),
            List.of(new RetrievalRuleService.SemanticLexiconEntry(
                "quote", "en", "price", List.of("index"), "metric", "market", 1, 0, false
            ))
        );

        assertThat(snapshot.keywordFrequencies())
            .extracting(RetrievalRuleService.KeywordFrequency::keyword,
                RetrievalRuleService.KeywordFrequency::frequency)
            .contains(
                org.assertj.core.groups.Tuple.tuple("index", 5),
                org.assertj.core.groups.Tuple.tuple("security", 2),
                org.assertj.core.groups.Tuple.tuple("quote", 2)
            );
    }

    @Test
    void indexReturnsOnlyKeywordCandidatesAndKeepsRegexRules() {
        RetrievalRuleService.IntentRule market = intent("market", List.of("index"), null);
        RetrievalRuleService.IntentRule unrelated = intent("support", List.of("timeout"), null);
        RetrievalRuleService.IntentRule regexOnly = intent("latest", List.of(), Pattern.compile("latest|最新"));
        RetrievalRuleService.RuleSnapshot snapshot = snapshot(
            List.of(market, unrelated, regexOnly),
            List.of(),
            List.of(),
            List.of()
        );

        assertThat(snapshot.matchingIntentRules("latest index quote", List.of("latest", "index", "quote")))
            .containsExactly(market, regexOnly);
    }

    @Test
    void queryTermFrequencyParticipatesInIntentScoring() {
        RetrievalRuleService ruleService = mock(RetrievalRuleService.class);
        when(ruleService.snapshot()).thenReturn(snapshot(
            List.of(
                intent("market", List.of("index"), null),
                intent("support", List.of("timeout"), null)
            ),
            List.of(),
            List.of(),
            List.of()
        ));
        SearchTokenizer tokenizer = mock(SearchTokenizer.class);
        QueryIntentClassifier classifier = new QueryIntentClassifier(tokenizer, ruleService);

        assertThat(classifier.classifyName(
            "index index timeout",
            List.of("index", "index", "timeout")
        )).isEqualTo("market");
    }

    @Test
    void chunkClassificationUsesIndexedCandidatesAndTermFrequency() {
        RetrievalRuleService ruleService = mock(RetrievalRuleService.class);
        when(ruleService.snapshot()).thenReturn(snapshot(
            List.of(),
            List.of(
                chunk("market", List.of("index"), null),
                chunk("support", List.of("timeout"), null)
            ),
            List.of(),
            List.of()
        ));
        ChunkTypeClassifier classifier = new ChunkTypeClassifier(ruleService);
        SearchDocument document = new SearchDocument();
        document.setTitle("index index");

        assertThat(classifier.classify(
            document,
            new TextChunker.TextChunk("timeout", "market")
        )).isEqualTo("market");
    }

    private RetrievalRuleService.RuleSnapshot snapshot(
        List<RetrievalRuleService.IntentRule> intentRules,
        List<RetrievalRuleService.ChunkRule> chunkRules,
        List<RetrievalRuleService.ExpandRule> expandRules,
        List<RetrievalRuleService.SemanticLexiconEntry> semanticLexicon
    ) {
        return new RetrievalRuleService.RuleSnapshot(
            intentRules,
            chunkRules,
            expandRules,
            semanticLexicon,
            System.currentTimeMillis()
        );
    }

    private RetrievalRuleService.IntentRule intent(String intent,
                                                   List<String> keywords,
                                                   Pattern pattern) {
        return new RetrievalRuleService.IntentRule(intent, keywords, pattern, 1, 0);
    }

    private RetrievalRuleService.ChunkRule chunk(String chunkType,
                                                 List<String> keywords,
                                                 Pattern pattern) {
        return new RetrievalRuleService.ChunkRule(chunkType, keywords, pattern, 1, 0);
    }
}
