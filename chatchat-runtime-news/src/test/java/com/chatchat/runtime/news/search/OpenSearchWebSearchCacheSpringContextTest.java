package com.chatchat.runtime.news.search;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchWebSearchCacheSpringContextTest {

    @Test
    void createsCacheBeanWhenOpenSearchIsEnabled() {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getOpenSearch().setEndpoint("http://127.0.0.1:9200");

        new ApplicationContextRunner()
            .withPropertyValues("chatchat.runtime.news.open-search.enabled=true")
            .withBean(NewsRuntimeProperties.class, () -> properties)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(CacheConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(OpenSearchWebSearchCache.class);
                assertThat(context).hasSingleBean(WebSearchCache.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(OpenSearchWebSearchCache.class)
    static class CacheConfiguration {
    }
}
