package com.chatchat.common.retrieval.query;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties
public class QueryOptimizationConfiguration {
    @Bean
    @ConfigurationProperties(prefix = "chatchat.search.query-optimization")
    public QueryOptimizationBudget queryOptimizationBudget() { return new QueryOptimizationBudget(); }

    @Bean(destroyMethod = "close")
    public SearchQueryOptimizer searchQueryOptimizer(QueryOptimizationBudget budget, ObjectProvider<QueryRefiner> refiner,
        ObjectProvider<QueryTermProtector> protectors) {
        // Vocabulary adapters can themselves use existing search-backed registries.
        // Resolve them on invocation, after the application graph has been constructed.
        QueryTermProtector vocabulary = new QueryTermProtector() {
            @Override public java.util.List<String> protectedPhrases(String source, int maximum) {
                return protectors.orderedStream().flatMap(provider -> provider.protectedPhrases(source, maximum).stream()).distinct().toList();
            }
            @Override public java.util.List<String> keywordCandidates(String source, int maximum) {
                return protectors.orderedStream().flatMap(provider -> provider.keywordCandidates(source, maximum).stream()).distinct().toList();
            }
        };
        return new SearchQueryOptimizer(budget, refiner.getIfAvailable(), java.util.List.of(vocabulary));
    }
}
