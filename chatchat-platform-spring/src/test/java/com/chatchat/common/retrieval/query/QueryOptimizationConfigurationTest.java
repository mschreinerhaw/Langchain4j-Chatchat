package com.chatchat.common.retrieval.query;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class QueryOptimizationConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(QueryOptimizationConfiguration.class);
    @Test void oneSharedOptimizerUsesBoundConfigurationAndModelIsOptIn() {
        context.withPropertyValues("chatchat.search.query-optimization.max-batches=7",
            "chatchat.search.query-optimization.total-timeout-ms=4500",
            "chatchat.search.query-optimization.protected-terms[0]=经营活动现金流")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(SearchQueryOptimizer.class).hasSingleBean(QueryOptimizationBudget.class);
                var budget = ctx.getBean(QueryOptimizationBudget.class);
                assertThat(ctx.getBean(SearchQueryOptimizer.class).budget()).isSameAs(budget);
                assertThat(budget.getMaxBatches()).isEqualTo(7);
                assertThat(budget.getTotalTimeoutMs()).isEqualTo(4500);
                assertThat(budget.getProtectedTerms()).containsExactly("经营活动现金流");
                assertThat(budget.isModelRefinementEnabled()).isFalse();
            });
    }
    @Test void invalidBudgetCannotSilentlyDisableProtection() {
        context.withPropertyValues("chatchat.search.query-optimization.max-batches=0")
            .run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void vocabularyAdaptersCanReuseExistingSearchRegistriesWithoutAStartupCycle() {
        context.withUserConfiguration(SearchBackedVocabulary.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(QueryTermProtector.class).protectedPhrases("registered company", 2)).containsExactly("registered company");
        });
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class SearchBackedVocabulary {
        @org.springframework.context.annotation.Bean QueryTermProtector registeredVocabulary(SearchQueryOptimizer optimizer) {
            return (source, maximum) -> java.util.List.of(source);
        }
    }
}
