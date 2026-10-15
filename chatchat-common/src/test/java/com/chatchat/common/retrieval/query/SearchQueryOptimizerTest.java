package com.chatchat.common.retrieval.query;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class SearchQueryOptimizerTest {
    @Test void independentUnitFailureReportsTheActualConfiguredLimitWithoutRelaxingIt() {
        var budget=new QueryOptimizationBudget();budget.setMaxBatches(3);
        try(var optimizer=new SearchQueryOptimizer(budget,null)) {
            optimizer.checkIndependentCount(3);
            assertThatThrownBy(()->optimizer.checkIndependentCount(4))
                .isInstanceOfSatisfying(QueryOptimizationException.class,error->
                    assertThat(error.diagnostic()).containsEntry("requestedBatches",4).containsEntry("maximumBatches",3));
        }
    }
    @Test void existingToolEnvelopesCanPersistTransformationEvidenceWithoutDuplicatingRawInputs() {
        try (var optimizer = new SearchQueryOptimizer()) {
            var result = optimizer.withAudit(() -> {
                optimizer.prepare(body("背景说明。".repeat(100) + "600519"), SearchQueryOptimizer.Mode.TEXT);
                return Map.of("items", List.of());
            });
            var audit = (Map<?,?>)result.get("queryOptimization");
            assertThat(audit.get("schemaVersion")).isEqualTo("query-optimization.v1");
            var transformations = (List<?>)audit.get("transformations");
            assertThat(transformations).hasSize(1);
            assertThat(((Map<?,?>)transformations.get(0)).get("originalQuerySha256")).isNotNull();
            assertThat(((Map<?,?>)transformations.get(0)).get("optimizedQuery").toString()).contains("600519");
        }
    }
    private Map<String,Object> body(String text) {
        return Map.of("size", 10, "query", Map.of("bool", Map.of("must", List.of(Map.of("multi_match", Map.of(
            "query", text, "fields", List.of("name^3", "description")))), "filter", List.of(
                Map.of("term", Map.of("tenantId", "tenant-A")), Map.of("range", Map.of("date", Map.of("gte", "2026-01-01")))),
            "must_not", List.of(Map.of("term", Map.of("status", "RETIRED"))))));
    }
    private Map<String,Object> response(List<Map<String,Object>> hits) { return Map.of("hits", Map.of("hits", hits)); }
    private Map<String,Object> hit(String id, double score) { return Map.of("_index", "assets", "_id", id, "_score", score, "_source", Map.of("id", id)); }

    @Test void shortQueryPassesThroughWithoutMutation() {
        try (var optimizer = new SearchQueryOptimizer()) {
            var input = body("公司 600519");
            var prepared = optimizer.prepare(input, SearchQueryOptimizer.Mode.TEXT);
            assertThat(prepared.body()).isEqualTo(input);
            assertThat(prepared.transformations()).isEmpty();
            optimizer.search(input, SearchQueryOptimizer.Mode.TEXT, (actual, timeout) -> {
                assertThat(actual.get("query")).isEqualTo(input.get("query"));
                assertThat(actual).containsKey("timeout"); return response(List.of());
            });
        }
    }

    @Test void fullTextExtractionRetainsTailEntitiesPhrasesTimeAndNumbers() {
        try (var optimizer = new SearchQueryOptimizer()) {
            String original = "请分析相关数据。".repeat(100) + " 贵州茅台股份有限公司 600519 2026-01-01 10% “经营活动现金流”";
            var prepared = optimizer.prepare(body(original), SearchQueryOptimizer.Mode.TEXT);
            assertThat(prepared.transformations()).hasSize(1);
            var trace = prepared.transformations().get(0);
            assertThat(trace).containsEntry("originalQuery", original).containsEntry("semanticGuarantee", "LEXICAL_APPROXIMATION");
            assertThat(trace.get("optimizedQuery").toString()).contains("贵州茅台股份有限公司", "600519", "2026-01-01", "10%", "经营活动现金流");
            var bool = (Map<?,?>)((Map<?,?>)prepared.body().get("query")).get("bool");
            var originalBool = (Map<?,?>)((Map<?,?>)body(original).get("query")).get("bool");
            assertThat(bool.get("filter")).isEqualTo(originalBool.get("filter"));
            assertThat(bool.get("must_not")).isEqualTo(originalBool.get("must_not"));
        }
    }

    @Test void modelExtractionCannotDropProtectedSpansOrInventKeywords() {
        var budget = new QueryOptimizationBudget(); budget.setModelRefinementEnabled(true);
        var calls = new AtomicInteger();
        try (var optimizer = new SearchQueryOptimizer(budget, (original, protectedTerms, limit, timeout) -> {
            calls.incrementAndGet(); return List.of("invented company", "现金流");
        })) {
            var prepared = optimizer.prepare(body("请分析经营活动现金流。".repeat(50) + " 000001 2026年"), SearchQueryOptimizer.Mode.TEXT);
            assertThat(prepared.transformations().get(0).get("optimizedQuery").toString()).contains("000001", "2026年", "现金流").doesNotContain("invented");
            assertThat(calls).hasValue(1);
            optimizer.prepare(body("short query"), SearchQueryOptimizer.Mode.TEXT); assertThat(calls).hasValue(1);
        }
    }

    @Test void explicitNegationInLongTextRequiresStructuredFilters() {
        try (var optimizer = new SearchQueryOptimizer()) {
            assertThatThrownBy(() -> optimizer.prepare(body("查询公司的经营情况。".repeat(50) + " 排除亏损企业"), SearchQueryOptimizer.Mode.TEXT))
                .isInstanceOf(QueryOptimizationException.class).hasMessageContaining("QUERY_REQUIRES_STRUCTURED_CONSTRAINTS");
        }
    }

    @Test void exactPhraseAndConjunctiveLongMatchesAreNotCompressed() {
        try (var optimizer = new SearchQueryOptimizer()) {
            String text = "important terms ".repeat(100);
            for (var query : List.of(Map.of("match_phrase", Map.of("title", text)),
                Map.of("multi_match", Map.of("query", text, "fields", List.of("title"), "operator", "and"))))
                assertThatThrownBy(() -> optimizer.prepare(Map.of("query", query), SearchQueryOptimizer.Mode.TEXT))
                    .isInstanceOf(QueryOptimizationException.class);
        }
    }

    @Test void protectedTermsOverBudgetAreRejectedRatherThanSilentlyDropped() {
        var budget = new QueryOptimizationBudget(); budget.setMaxKeywords(2);
        try (var optimizer = new SearchQueryOptimizer(budget, null)) {
            assertThatThrownBy(() -> optimizer.prepare(body("背景内容。".repeat(100) + " 000001 600519 2026年"), SearchQueryOptimizer.Mode.TEXT))
                .hasMessageContaining("PROTECTED_TERMS_EXCEED_BUDGET");
        }
    }

    @Test void existingDslRetainsAndOrNotAndIsNeverRewritten() {
        try (var optimizer = new SearchQueryOptimizer()) {
            var query = Map.of("size", 4, "query", Map.of("bool", Map.of(
                "must", List.of(Map.of("match", Map.of("name", "A")), Map.of("match", Map.of("name", "B"))),
                "should", List.of(Map.of("term", Map.of("kind", "C")), Map.of("term", Map.of("kind", "D"))),
                "minimum_should_match", 2, "must_not", List.of(Map.of("term", Map.of("status", "DELETED"))))));
            assertThat(optimizer.prepare(query, SearchQueryOptimizer.Mode.DSL).body()).isEqualTo(query);
            assertThat(optimizer.prepare(query, SearchQueryOptimizer.Mode.DSL).transformations()).isEmpty();
        }
    }

    @Test void independentQueriesRetainFiltersDeduplicateAndExposeFusionEvidence() {
        try (var optimizer = new SearchQueryOptimizer()) {
            var queries = List.of(body("first topic"), body("second topic"));
            var result = optimizer.searchIndependent(queries, SearchQueryOptimizer.Mode.TEXT, (actual, timeout, concurrency) -> {
                assertThat(actual).hasSize(2);
                for (int i=0; i<actual.size(); i++) assertThat(actual.get(i).get("query")).isEqualTo(queries.get(i).get("query"));
                assertThat(concurrency).isEqualTo(optimizer.budget().getMaxConcurrent());
                return Map.of("responses", List.of(response(List.of(hit("same", 10), hit("only-one", 8))),
                    response(List.of(hit("same", 7), hit("only-two", 6)))));
            });
            assertThat(result.status()).isEqualTo(SearchQueryOptimizer.Status.SUCCEEDED);
            assertThat(result.hits()).hasSize(3);
            assertThat(result.hits().get(0)).containsEntry("_id", "same");
            assertThat((List<?>)result.hits().get(0).get("batchEvidence")).hasSize(2);
        }
    }

    @Test void partialBatchAndShardFailuresAreNotCompleteSuccess() {
        try (var optimizer = new SearchQueryOptimizer()) {
            var result = optimizer.searchIndependent(List.of(body("a"), body("b")), SearchQueryOptimizer.Mode.TEXT,
                (actual, timeout, concurrency) -> Map.of("responses", List.of(response(List.of(hit("valid", 2))), Map.of("error", "timeout"))));
            assertThat(result.status()).isEqualTo(SearchQueryOptimizer.Status.PARTIAL);
            assertThat(result.hits()).hasSize(1);
            assertThat(result.batches().get(1).status()).isEqualTo("FAILED");
            assertThatThrownBy(() -> optimizer.search(body("a"), SearchQueryOptimizer.Mode.TEXT,
                (actual, timeout) -> Map.of("hits", Map.of("hits", List.of()), "_shards", Map.of("failed", 1))))
                .hasMessageContaining("QUERY_PARTIAL_SHARDS");
        }
    }

    @Test void batchBudgetIsCheckedBeforeAnyRequest() {
        var budget = new QueryOptimizationBudget(); budget.setMaxBatches(1);
        var called = new AtomicBoolean();
        try (var optimizer = new SearchQueryOptimizer(budget, null)) {
            assertThatThrownBy(() -> optimizer.searchIndependent(List.of(body("a"), body("b")), SearchQueryOptimizer.Mode.TEXT,
                (b, t, c) -> { called.set(true); return Map.of(); })).hasMessageContaining("QUERY_BATCH_BUDGET_EXCEEDED");
            assertThat(called).isFalse();
        }
    }

    @Test void nestedSearchesShareOverallRequestBudgetAndScopeDoesNotLeak() {
        var budget = new QueryOptimizationBudget(); budget.setMaxBatches(1);
        try (var optimizer = new SearchQueryOptimizer(budget, null)) {
            try (var scope = optimizer.openScope()) {
                optimizer.search(body("a"), SearchQueryOptimizer.Mode.TEXT, (b,t) -> response(List.of()));
                assertThatThrownBy(() -> optimizer.search(body("b"), SearchQueryOptimizer.Mode.TEXT, (b,t) -> response(List.of())))
                    .hasMessageContaining("QUERY_BATCH_BUDGET_EXCEEDED");
            }
            optimizer.search(body("new call"), SearchQueryOptimizer.Mode.TEXT, (b,t) -> response(List.of()));
        }
    }

    @Test void totalBudgetIncludesPreprocessingAndCanBeTestedWithoutSleeping() {
        var nanos = new AtomicLong(); var budget = new QueryOptimizationBudget(); budget.setTotalTimeoutMs(10);
        try (var optimizer = new SearchQueryOptimizer(budget, null, nanos::get); var scope = optimizer.openScope()) {
            nanos.set(TimeUnit.MILLISECONDS.toNanos(11));
            assertThatThrownBy(() -> optimizer.search(body("a"), SearchQueryOptimizer.Mode.TEXT, (b,t) -> response(List.of())))
                .hasMessageContaining("QUERY_TOTAL_TIMEOUT");
        }
    }

    @Test void transportTimeoutCancelsUnderlyingWork() throws Exception {
        var budget = new QueryOptimizationBudget(); budget.setRequestTimeoutMs(30);
        var cancelled = new CountDownLatch(1);
        try (var optimizer = new SearchQueryOptimizer(budget, null)) {
            assertThatThrownBy(() -> optimizer.search(body("a"), SearchQueryOptimizer.Mode.TEXT, (b,t) -> {
                try { new CountDownLatch(1).await(); } catch (InterruptedException e) { cancelled.countDown(); throw e; }
                return response(List.of());
            })).hasMessageContaining("QUERY_TIMEOUT");
            assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void stricterPublishedTimeoutIsPreserved() {
        try (var optimizer = new SearchQueryOptimizer()) {
            var input = new LinkedHashMap<>(body("a")); input.put("timeout", "500ms");
            optimizer.search(input, SearchQueryOptimizer.Mode.DSL, (b,t) -> { assertThat(b).containsEntry("timeout", "500ms"); assertThat(t).isLessThanOrEqualTo(500); return response(List.of()); });
        }
    }

    @Test void registeredVocabularyProtectsCompanyShortNamesAndUsesExistingKeywordCandidates() {
        QueryTermProtector vocabulary = new QueryTermProtector() {
            public List<String> protectedPhrases(String source, int maximum) { return List.of("贵州茅台"); }
            public List<String> keywordCandidates(String source, int maximum) { return List.of("现金流"); }
        };
        try (var optimizer = new SearchQueryOptimizer(new QueryOptimizationBudget(), null, List.of(vocabulary))) {
            var prepared = optimizer.prepare(body("无关背景描述。".repeat(100) + " 贵州茅台 现金流"), SearchQueryOptimizer.Mode.TEXT);
            assertThat(prepared.transformations().get(0).get("optimizedQuery").toString()).contains("贵州茅台", "现金流");
        }
    }

    @Test void modelRefinementHasOneSharedCallAllowanceAcrossIndependentIntents() {
        var budget = new QueryOptimizationBudget(); budget.setModelRefinementEnabled(true); budget.setMaxRefinementCalls(1);
        var calls = new AtomicInteger();
        try (var optimizer = new SearchQueryOptimizer(budget, (source, protectedPhrases, max, timeout) -> { calls.incrementAndGet(); return List.of("现金流"); });
             var scope = optimizer.openScope()) {
            optimizer.prepare(body("背景说明。".repeat(100) + "现金流"), SearchQueryOptimizer.Mode.TEXT);
            optimizer.prepare(body("另一段说明。".repeat(100) + "现金流"), SearchQueryOptimizer.Mode.TEXT);
            assertThat(calls).hasValue(1);
        }
    }

    @Test void maliciousDslAndLargeCandidateRequestsHaveExplicitDiagnostics() {
        try (var optimizer = new SearchQueryOptimizer()) {
            assertThatThrownBy(() -> optimizer.prepare(Map.of("size", 100000, "query", Map.of("match_all", Map.of())), SearchQueryOptimizer.Mode.DSL))
                .hasMessageContaining("QUERY_CANDIDATE_BUDGET_EXCEEDED");
            assertThatThrownBy(() -> optimizer.prepare(Map.of("query", Map.of("multi_match", Map.of("query", "a", "fields", List.of("*")))), SearchQueryOptimizer.Mode.DSL))
                .hasMessageContaining("QUERY_UNBOUNDED_FIELDS");
            var nested = new LinkedHashMap<String,Object>(); nested.put("query", Map.of());
            for (int i=0;i<30;i++) nested = new LinkedHashMap<>(Map.of("bool", nested));
            var finalNested = nested;
            assertThatThrownBy(() -> optimizer.prepare(finalNested, SearchQueryOptimizer.Mode.DSL)).hasMessageContaining("QUERY_DSL_BUDGET_EXCEEDED");
        }
    }
}
