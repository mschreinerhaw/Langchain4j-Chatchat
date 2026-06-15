package com.chatchat.tools.builtin;

import com.chatchat.agents.tool.DefaultToolRegistry;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BuiltInToolsBootstrapTest {

    private HttpServer server;
    private int documentLoginCount;
    private String documentSearchAuthorization;
    private String documentDetailAuthorization;
    private String searchEngineQuery;
    private String siteSearchKeyword;
    private String siteSearchRawQuery;
    private int searchLandingCount;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void calculatorAndWebSearchExposeGovernanceMetadata() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            new WebSearchToolProperties(),
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );

        bootstrap.initializeBuiltInTools();

        ToolMetadata calculator = registry.getToolMetadata("calculator");
        assertThat(calculator.getCategory()).isEqualTo("utility_calculation");
        assertThat(calculator.getRiskLevel()).isEqualTo("low");
        assertThat(calculator.getOperationType()).isEqualTo("read");
        assertThat(calculator.getConfirmation()).containsEntry("default", "auto_execute");
        assertThat(calculator.getInputPolicy()).containsEntry("must_show_parameters", true);

        ToolMetadata webSearch = registry.getToolMetadata("web_search");
        assertThat(webSearch.getCategory()).isEqualTo("public_web_search");
        assertThat(webSearch.getRiskLevel()).isEqualTo("low");
        assertThat(webSearch.getOperationType()).isEqualTo("read");
        assertThat(webSearch.getConfirmation()).containsEntry("default", "auto_execute");
        assertThat(webSearch.getInputPolicy()).containsEntry("must_show_parameters", true);
        assertThat(webSearch.getInputPolicy()).containsKey("sensitive_params");
        assertThat(webSearch.getTimeoutMillis()).isGreaterThanOrEqualTo(0L);

        ToolMetadata databaseQuery = registry.getToolMetadata("database_query");
        assertThat(databaseQuery.getCategory()).isEqualTo("database_data_query");
        assertThat(databaseQuery.getRiskLevel()).isEqualTo("high");
        assertThat(databaseQuery.getOperationType()).isEqualTo("read");
        assertThat(databaseQuery.isUserVisible()).isFalse();
        assertThat(databaseQuery.isAgentCompatible()).isFalse();
        assertThat(databaseQuery.getConfirmation()).containsEntry("default", "ask_before_execute");
        assertThat(databaseQuery.getInputPolicy()).containsEntry("allow_auto_fill", false);
        assertThat(databaseQuery.getOutputPolicy()).containsKey("mask_fields");

        ToolMetadata fileSystem = registry.getToolMetadata("file_system");
        assertThat(fileSystem.getCategory()).isEqualTo("local_file_system");
        assertThat(fileSystem.getRiskLevel()).isEqualTo("high");
        assertThat(fileSystem.getOperationType()).isEqualTo("read");
        assertThat(fileSystem.getConfirmation()).containsEntry("default", "ask_before_execute");
        assertThat(fileSystem.getInputPolicy()).containsEntry("allow_auto_fill", false);
        assertThat(fileSystem.getOutputPolicy()).containsKey("mask_fields");
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentSearchEnrichesResultsWithDocumentContentExcerpts() throws Exception {
        startDocumentApi();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        MockEnvironment environment = new MockEnvironment()
            .withProperty("chatchat.tools.document-search.api-base-url", "http://localhost:" + server.getAddress().getPort())
            .withProperty("chatchat.tools.document-search.default-excerpt-chars", "120")
            .withProperty("chatchat.tools.document-search.max-excerpt-chars", "200");

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            new WebSearchToolProperties(),
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            environment,
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        ToolRegistry.EnhancedTool documentSearch = registry.getEnhancedTool("document_search");
        ToolOutput output = documentSearch.execute(ToolInput.builder()
            .parameters(Map.of("query", "Studio config file update", "limit", 1))
            .build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("contentMode", "detail_enriched");
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0))
            .containsEntry("detailFetched", true)
            .containsEntry("contentAvailable", true);
        assertThat((String) results.get(0).get("contentExcerpt"))
            .contains("application.yml")
            .contains("livedata-stream");
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) data.get("evidenceSnippets");
        assertThat(evidence).hasSize(1);
        assertThat((String) evidence.get(0).get("excerpt")).contains("application.yml");
        assertThat(documentLoginCount).isEqualTo(1);
        assertThat(documentSearchAuthorization).isEqualTo("Bearer doc-token");
        assertThat(documentDetailAuthorization).isEqualTo("Bearer doc-token");
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchFetchesPageContentExcerpts() throws Exception {
        startWebSearchApi();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        WebSearchToolProperties webSearchProperties = new WebSearchToolProperties();
        webSearchProperties.setProvider("bing_html");
        webSearchProperties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/search");
        webSearchProperties.setMaxResults(3);
        webSearchProperties.setFetchPages(true);
        webSearchProperties.setMaxPagesToFetch(1);
        webSearchProperties.setPageExcerptChars(180);
        webSearchProperties.getBrowser().setEnabled(false);
        webSearchProperties.getSiteSearch().setEnabled(false);

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            webSearchProperties,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        ToolRegistry.EnhancedTool webSearch = registry.getEnhancedTool("web_search");
        ToolOutput output = webSearch.execute(ToolInput.builder()
            .parameters(Map.of("query", "Kunpeng", "num_results", 1))
            .build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data)
            .containsEntry("contentMode", "page_enriched")
            .containsEntry("page_excerpt_count", 1);
        assertThat(searchLandingCount).isGreaterThanOrEqualTo(1);
        assertThat(searchEngineQuery).isEqualTo("Kunpeng");
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).doesNotContainKeys("pageFetched", "pageExcerpt");
        List<Map<String, Object>> excerpts = (List<Map<String, Object>>) data.get("pageExcerpts");
        assertThat(excerpts).hasSize(1);
        assertThat((String) excerpts.get(0).get("excerpt")).contains("ARM compatibility");
        assertThat((List<Map<String, Object>>) data.get("pageEvidence")).hasSize(1);
        assertThat((List<Map<String, Object>>) data.get("primaryResults")).hasSize(1);
        assertThat((List<Map<String, Object>>) data.get("finalResults")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchOutputsStructuredTextAndRelevanceMetadata() throws Exception {
        startWebSearchApi();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        WebSearchToolProperties webSearchProperties = new WebSearchToolProperties();
        webSearchProperties.setProvider("bing_html");
        webSearchProperties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/search");
        webSearchProperties.setMaxResults(3);
        webSearchProperties.setFetchPages(false);
        webSearchProperties.getBrowser().setEnabled(false);
        webSearchProperties.getSiteSearch().setEnabled(false);

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            webSearchProperties,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        ToolRegistry.EnhancedTool webSearch = registry.getEnhancedTool("web_search");
        ToolOutput output = webSearch.execute(ToolInput.builder()
            .parameters(Map.of("query", "Kunpeng", "num_results", 1))
            .build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat((List<Map<String, Object>>) data.get("results")).hasSize(1);
        assertThat(data).containsEntry("search_result_useful", true);
        assertThat((String) data.get("structured_text"))
            .contains("results_returned_for_model_judgment: 1")
            .contains("Kunpeng compatibility guide")
            .doesNotContain("keywordMatched")
            .doesNotContain("matched_results")
            .doesNotContain("keywords:");
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchTreatsSearchEngineUrlAsSubmittedQuery() throws Exception {
        startWebSearchApi();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        WebSearchToolProperties webSearchProperties = new WebSearchToolProperties();
        webSearchProperties.setProvider("bing_html");
        webSearchProperties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/search");
        webSearchProperties.setMaxResults(3);
        webSearchProperties.setFetchPages(false);
        webSearchProperties.getBrowser().setEnabled(false);
        webSearchProperties.getSiteSearch().setEnabled(false);

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            webSearchProperties,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        String searchQuery = "\u4eba\u5de5\u667a\u80fd\u677f\u5757\u884c\u60c5 \u6700\u65b0";
        ToolRegistry.EnhancedTool webSearch = registry.getEnhancedTool("web_search");
        ToolOutput output = webSearch.execute(ToolInput.builder()
            .parameters(Map.of(
                "query", "https://cn.bing.com/search query=" + searchQuery,
                "num_results", 1
            ))
            .build());

        assertThat(output.isSuccess()).isTrue();
        assertThat(searchLandingCount).isGreaterThanOrEqualTo(1);
        assertThat(searchEngineQuery).isEqualTo(searchQuery);
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data)
            .containsEntry("search_query", searchQuery)
            .containsEntry("site_search_query", searchQuery);
        assertThat(data.get("target_site")).isNull();
        assertThat((List<Map<String, Object>>) data.get("results")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchAcceptsDirectBingSearchUrlAndKeepsChineseQueryRaw() throws Exception {
        startWebSearchApi();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        WebSearchToolProperties webSearchProperties = new WebSearchToolProperties();
        webSearchProperties.setProvider("bing_html");
        webSearchProperties.setEndpoint("http://localhost:" + server.getAddress().getPort()
            + "/search?q=\u9876\u70b9\u8f6f\u4ef6\u6700\u65b0\u516c\u544a");
        webSearchProperties.setMaxResults(3);
        webSearchProperties.setFetchPages(false);
        webSearchProperties.getBrowser().setEnabled(false);
        webSearchProperties.getSiteSearch().setEnabled(false);

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            webSearchProperties,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        ToolRegistry.EnhancedTool webSearch = registry.getEnhancedTool("web_search");
        ToolOutput output = webSearch.execute(ToolInput.builder()
            .parameters(Map.of("query", "\u9876\u70b9\u8f6f\u4ef6\u6700\u65b0\u516c\u544a", "num_results", 1))
            .build());

        assertThat(output.isSuccess()).isTrue();
        assertThat(searchLandingCount).isZero();
        assertThat(searchEngineQuery).isEqualTo("\u9876\u70b9\u8f6f\u4ef6\u6700\u65b0\u516c\u544a");
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat((List<Map<String, Object>>) data.get("results")).hasSize(1);

        Method rawUrlBuilder = webSearch.getClass()
            .getDeclaredMethod("urlWithRawQueryParams", String.class, Map.class);
        rawUrlBuilder.setAccessible(true);
        assertThat((String) rawUrlBuilder.invoke(webSearch,
            "https://cn.bing.com/search",
            Map.of("q", "\u9876\u70b9\u8f6f\u4ef6\u6700\u65b0\u516c\u544a")))
            .isEqualTo("https://cn.bing.com/search?q=\u9876\u70b9\u8f6f\u4ef6\u6700\u65b0\u516c\u544a");
        assertThat((String) rawUrlBuilder.invoke(webSearch,
            "https://example.com/search",
            Map.of("keyword", "ACME/603383 latest report")))
            .isEqualTo("https://example.com/search?keyword=ACME/603383 latest report");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bingProviderOpensSearchLandingAndReplaysFormParameters() throws Exception {
        WebSearchTool webSearch = new WebSearchTool(new WebSearchToolProperties(), new ObjectMapper());

        Method landingUrl = webSearch.getClass().getDeclaredMethod("bingSearchLandingUrl", String.class);
        landingUrl.setAccessible(true);
        assertThat((String) landingUrl.invoke(webSearch, "https://cn.bing.com/search?q=old"))
            .isEqualTo("https://cn.bing.com/search");
        assertThat((String) landingUrl.invoke(webSearch, "https://www.bing.com/"))
            .isEqualTo("https://www.bing.com/search");

        Method queryParams = webSearch.getClass().getDeclaredMethod("bingSearchQueryParams", String.class);
        queryParams.setAccessible(true);
        Map<String, String> params = (Map<String, String>) queryParams.invoke(
            webSearch,
            "\u4eba\u5de5\u667a\u80fd\u677f\u5757\u884c\u60c5 \u6700\u65b0"
        );
        assertThat(params)
            .containsEntry("go", "\u641c\u7d22")
            .containsEntry("q", "\u4eba\u5de5\u667a\u80fd\u677f\u5757\u884c\u60c5 \u6700\u65b0")
            .containsEntry("qs", "n")
            .containsEntry("form", "QBRE")
            .containsEntry("sp", "-1")
            .containsEntry("lq", "0")
            .containsEntry("pq", "")
            .containsEntry("sc", "0-0")
            .containsEntry("sk", "")
            .containsEntry("mkt", "zh-CN")
            .containsEntry("setlang", "zh-Hans")
            .containsEntry("cc", "CN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchSubmitsDiscoveredSiteSearchForms() throws Exception {
        startWebSearchApiWithSiteSearchForm();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        WebSearchToolProperties webSearchProperties = new WebSearchToolProperties();
        webSearchProperties.setProvider("bing_html");
        webSearchProperties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/search");
        webSearchProperties.setMaxResults(5);
        webSearchProperties.setFetchPages(false);
        webSearchProperties.getBrowser().setEnabled(false);
        webSearchProperties.getSiteSearch().setMaxPagesToInspect(1);
        webSearchProperties.getSiteSearch().setMaxSecondaryPages(1);
        webSearchProperties.getSiteSearch().setMaxLinksPerPage(3);

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            webSearchProperties,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        ToolRegistry.EnhancedTool webSearch = registry.getEnhancedTool("web_search");
        ToolOutput output = webSearch.execute(ToolInput.builder()
            .parameters(Map.of("query", "600519", "num_results", 3))
            .build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data)
            .containsEntry("contentMode", "site_search_enriched")
            .containsEntry("site_search_result_count", 1);
        List<String> referenceUrls = (List<String>) data.get("reference_urls");
        assertThat(referenceUrls).anyMatch(url -> url.endsWith("/security/600519"));
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).anySatisfy(result -> assertThat(result)
            .containsEntry("source", "site_search")
            .containsEntry("url", "http://localhost:" + server.getAddress().getPort() + "/security/600519"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchPrimaryStageReturnsOnlySearchSnippets() throws Exception {
        startWebSearchApiWithSiteSearchForm();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        WebSearchToolProperties webSearchProperties = new WebSearchToolProperties();
        webSearchProperties.setProvider("bing_html");
        webSearchProperties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/search");
        webSearchProperties.setMaxResults(5);
        webSearchProperties.setFetchPages(true);
        webSearchProperties.getBrowser().setEnabled(false);
        webSearchProperties.getSiteSearch().setMaxPagesToInspect(1);
        webSearchProperties.getSiteSearch().setMaxSecondaryPages(1);

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            webSearchProperties,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        ToolRegistry.EnhancedTool webSearch = registry.getEnhancedTool("web_search");
        ToolOutput output = webSearch.execute(ToolInput.builder()
            .parameters(Map.of(
                "query", "600519",
                "num_results", 3,
                "search_stage", "primary"
            ))
            .build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data)
            .containsEntry("search_stage", "primary")
            .containsEntry("contentMode", "search_snippets_only")
            .containsEntry("site_search_result_count", 0)
            .containsEntry("page_fetch_enabled", false)
            .containsEntry("page_excerpt_count", 0);
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0))
            .containsEntry("url", "http://localhost:" + server.getAddress().getPort() + "/exchange");
        assertThat((String) data.get("structured_text"))
            .contains("Example securities exchange")
            .doesNotContain("/security/600519");
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchSiteSearchStageUsesSelectedSeedUrls() throws Exception {
        startWebSearchApiWithSiteSearchForm();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        WebSearchToolProperties webSearchProperties = new WebSearchToolProperties();
        webSearchProperties.setProvider("bing_html");
        webSearchProperties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/search");
        webSearchProperties.setMaxResults(5);
        webSearchProperties.setFetchPages(false);
        webSearchProperties.getBrowser().setEnabled(false);
        webSearchProperties.getSiteSearch().setMaxPagesToInspect(1);
        webSearchProperties.getSiteSearch().setMaxSecondaryPages(1);
        webSearchProperties.getSiteSearch().setMaxLinksPerPage(3);

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            webSearchProperties,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        String seedUrl = "http://localhost:" + server.getAddress().getPort() + "/exchange";
        ToolRegistry.EnhancedTool webSearch = registry.getEnhancedTool("web_search");
        ToolOutput output = webSearch.execute(ToolInput.builder()
            .parameters(Map.of(
                "query", "600519/ABC",
                "num_results", 3,
                "search_stage", "site_search",
                "seed_urls", List.of(seedUrl)
            ))
            .build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data)
            .containsEntry("search_stage", "site_search")
            .containsEntry("contentMode", "site_search_enriched")
            .containsEntry("site_search_result_count", 1)
            .containsEntry("page_fetch_enabled", false);
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).anySatisfy(result -> assertThat(result)
            .containsEntry("source", "site_search")
            .containsEntry("url", "http://localhost:" + server.getAddress().getPort() + "/security/600519"));
        assertThat(siteSearchRawQuery)
            .contains("keyword=600519/ABC")
            .doesNotContain("600519%2FABC")
            .doesNotContain("600519%252FABC");
        assertThat((List<Map<String, Object>>) data.get("seed_results"))
            .anySatisfy(result -> assertThat(result).containsEntry("url", seedUrl));
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchUsesKnownJsonpSiteSearchEndpoint() throws Exception {
        startWebSearchApiWithKnownJsonpSiteSearch();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        WebSearchToolProperties webSearchProperties = new WebSearchToolProperties();
        webSearchProperties.setProvider("bing_html");
        webSearchProperties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/search");
        webSearchProperties.setMaxResults(5);
        webSearchProperties.setFetchPages(false);
        webSearchProperties.getBrowser().setEnabled(false);
        webSearchProperties.getSiteSearch().setMaxPagesToInspect(1);
        webSearchProperties.getSiteSearch().setMaxSecondaryPages(1);
        webSearchProperties.getSiteSearch().setMaxLinksPerPage(3);

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            webSearchProperties,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        ToolRegistry.EnhancedTool webSearch = registry.getEnhancedTool("web_search");
        String targetUrl = "http://localhost:" + server.getAddress().getPort() + "/exchange-js";
        ToolOutput output = webSearch.execute(ToolInput.builder()
            .parameters(Map.of("query", "find " + targetUrl + " ACME/603383", "num_results", 3))
            .build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data)
            .containsEntry("search_query", "site:localhost ACME/603383")
            .containsEntry("site_search_query", "ACME/603383")
            .containsEntry("contentMode", "site_search_enriched")
            .containsEntry("site_search_result_count", 1);
        assertThat(searchEngineQuery).isEqualTo("site:localhost ACME/603383");
        assertThat(siteSearchKeyword).isEqualTo("ACME/603383");
        assertThat(siteSearchRawQuery)
            .contains("keyword=ACME%2F603383")
            .doesNotContain("ACME%252F603383");
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).anySatisfy(result -> assertThat(result)
            .containsEntry("source", "site_search_known")
            .containsEntry("url", "http://localhost:" + server.getAddress().getPort() + "/disclosure/603383_annual_summary.pdf"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchDoesNotUseTargetUrlAsSiteSearchSeedWhenSearchEngineResultsDrift() throws Exception {
        startWebSearchApiWithOffDomainResultsAndKnownSearchTarget();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        WebSearchToolProperties webSearchProperties = new WebSearchToolProperties();
        webSearchProperties.setProvider("bing_html");
        webSearchProperties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/search");
        webSearchProperties.setMaxResults(5);
        webSearchProperties.setFetchPages(false);
        webSearchProperties.getBrowser().setEnabled(false);
        webSearchProperties.getSiteSearch().setMaxPagesToInspect(1);
        webSearchProperties.getSiteSearch().setMaxSecondaryPages(1);
        webSearchProperties.getSiteSearch().setMaxLinksPerPage(3);

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            webSearchProperties,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        ToolRegistry.EnhancedTool webSearch = registry.getEnhancedTool("web_search");
        String targetUrl = "http://localhost:" + server.getAddress().getPort() + "/exchange-js";
        ToolOutput output = webSearch.execute(ToolInput.builder()
            .parameters(Map.of("query", "find " + targetUrl + " 603383", "num_results", 3))
            .build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data)
            .containsEntry("search_query", "site:localhost 603383")
            .containsEntry("site_search_query", "603383")
            .containsEntry("target_site", "localhost")
            .containsEntry("site_search_result_count", 0)
            .containsEntry("count", 1);
        assertThat(searchEngineQuery).isEqualTo("site:localhost 603383");
        assertThat(siteSearchKeyword).isNull();
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0))
            .containsEntry("url", "https://www.kimi.com/zh/")
            .containsEntry("targetDomainMatched", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void webSearchDiscoversSearchEntrypointAndKeepsDocumentResults() throws Exception {
        startWebSearchApiWithSearchEntrypointAndDocuments();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        WebSearchToolProperties webSearchProperties = new WebSearchToolProperties();
        webSearchProperties.setProvider("bing_html");
        webSearchProperties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/engine");
        webSearchProperties.setMaxResults(5);
        webSearchProperties.setFetchPages(false);
        webSearchProperties.getBrowser().setEnabled(false);
        webSearchProperties.getSiteSearch().setMaxPagesToInspect(3);
        webSearchProperties.getSiteSearch().setMaxSecondaryPages(2);
        webSearchProperties.getSiteSearch().setMaxLinksPerPage(5);

        BuiltInToolsBootstrap bootstrap = new BuiltInToolsBootstrap(
            registry,
            webSearchProperties,
            new DatabaseToolProperties(),
            mock(DynamicJdbcDriverLoader.class),
            new MockEnvironment(),
            new ObjectMapper()
        );
        bootstrap.initializeBuiltInTools();

        ToolRegistry.EnhancedTool webSearch = registry.getEnhancedTool("web_search");
        ToolOutput output = webSearch.execute(ToolInput.builder()
            .parameters(Map.of("query", "ACME-2025", "num_results", 4))
            .build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data)
            .containsEntry("contentMode", "site_search_enriched")
            .containsEntry("site_search_result_count", 2);
        List<String> referenceUrls = (List<String>) data.get("reference_urls");
        assertThat(referenceUrls)
            .anyMatch(url -> url.endsWith("/files/acme-2025-report.pdf"))
            .anyMatch(url -> url.endsWith("/files/acme-2025-data.xlsx"));
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        assertThat(results).anySatisfy(result -> assertThat(result)
            .containsEntry("source", "site_search")
            .containsEntry("url", "http://localhost:" + server.getAddress().getPort() + "/files/acme-2025-report.pdf"));
        assertThat(results).anySatisfy(result -> assertThat(result)
            .containsEntry("source", "site_search")
            .containsEntry("url", "http://localhost:" + server.getAddress().getPort() + "/files/acme-2025-data.xlsx"));
    }

    private void startDocumentApi() throws IOException {
        documentLoginCount = 0;
        documentSearchAuthorization = null;
        documentDetailAuthorization = null;
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/enterprise/auth/login", exchange -> {
            documentLoginCount++;
            String body = """
                {"code":200,"message":"login success","data":{"token":"doc-token","user":{"username":"admin"}}}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/v1/search", exchange -> {
            documentSearchAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer doc-token".equals(documentSearchAuthorization)) {
                byte[] bytes = "{\"code\":401,\"message\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(401, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            String body = """
                {"data":{"keyword":"Studio config file update","results":[{"docId":"doc-1","title":"LiveData Studio Deployment","summary":"summary only","detailPath":"/api/v1/search/documents/doc-1"}],"total":1,"limit":1}}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/v1/search/documents/doc-1", exchange -> {
            documentDetailAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer doc-token".equals(documentDetailAuthorization)) {
                byte[] bytes = "{\"code\":401,\"message\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(401, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            String body = """
                {"data":{"docId":"doc-1","title":"LiveData Studio Deployment","content":"Deployment requires editing Studio config file application.yml, copying livedata-stream to /home/livedata, configuring connection, port, and service path, then restarting Prometheus service."}}
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private void startWebSearchApi() throws IOException {
        searchLandingCount = 0;
        searchEngineQuery = null;
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            String query = queryParam(exchange.getRequestURI().getRawQuery(), "q");
            String body;
            if (query == null || query.isBlank()) {
                searchLandingCount++;
                body = """
                    <html><body><form action="/search" method="get"><input type="search" name="q" aria-label="Search"><button type="submit">Search</button></form></body></html>
                    """;
            } else {
                searchEngineQuery = query;
                body = """
                    <html><body><ol><li class="b_algo"><h2><a href="http://localhost:%d/page1">Kunpeng compatibility guide</a></h2><div class="b_caption"><p>Compatibility notes for ARM.</p></div></li></ol></body></html>
                    """.formatted(server.getAddress().getPort());
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/page1", exchange -> {
            String body = """
                <html><head><title>Kunpeng compatibility guide</title></head><body><header>Navigation</header><main>Huawei Kunpeng CPU uses an ARM architecture. This page lists ARM compatibility requirements, operating system support, and deployment notes for enterprise software.</main><footer>Footer</footer></body></html>
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private void startWebSearchApiWithSiteSearchForm() throws IOException {
        siteSearchRawQuery = null;
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            String body = """
                <html><body><ol><li class="b_algo"><h2><a href="http://localhost:%d/exchange">Example securities exchange</a></h2><div class="b_caption"><p>Search listed securities on this exchange.</p></div></li></ol></body></html>
                """.formatted(server.getAddress().getPort());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/exchange", exchange -> {
            String body = """
                <html><body><main><h1>Exchange</h1><form action="/exchange/search" method="get"><input type="search" name="keyword" placeholder="Search security code or name"><button type="submit">Search</button></form></main></body></html>
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/exchange/search", exchange -> {
            siteSearchRawQuery = exchange.getRequestURI().getRawQuery();
            String body = """
                <html><body><main class="search-results"><h1>Search results</h1><article><a href="/security/600519">Kweichow Moutai 600519</a><p>Listed security profile and exchange disclosure links.</p></article></main></body></html>
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private void startWebSearchApiWithSearchEntrypointAndDocuments() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/engine", exchange -> {
            String body = """
                <html><body><ol><li class="b_algo"><h2><a href="http://localhost:%d/company">ACME company homepage</a></h2><div class="b_caption"><p>Corporate homepage without the requested filing.</p></div></li></ol></body></html>
                """.formatted(server.getAddress().getPort());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/company", exchange -> {
            String body = """
                <html><body><main><h1>ACME</h1><p>Welcome to ACME.</p><a href="/home/search">Site Search</a></main></body></html>
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/home/search", exchange -> {
            String body = """
                <html><body><main><h1>Search</h1><form action="/home/search/results" method="get"><input name="q" type="search" placeholder="Search files"><button>Search</button></form></main></body></html>
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/home/search/results", exchange -> {
            String body = """
                <html><body><main class="search-results"><article><a href="/files/acme-2025-report.pdf">ACME-2025 annual report PDF</a><p>Annual report filing.</p></article><article><a href="/files/acme-2025-data.xlsx">ACME-2025 financial data Excel</a><p>Workbook attachment.</p></article></main></body></html>
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private void startWebSearchApiWithKnownJsonpSiteSearch() throws IOException {
        searchEngineQuery = null;
        siteSearchKeyword = null;
        siteSearchRawQuery = null;
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            searchEngineQuery = queryParam(exchange.getRequestURI().getRawQuery(), "q");
            String body = """
                <html><body><ol><li class="b_algo"><h2><a href="http://localhost:%d/exchange-js">Exchange JS search</a></h2><div class="b_caption"><p>Search listed company disclosures.</p></div></li></ol></body></html>
                """.formatted(server.getAddress().getPort());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/exchange-js", exchange -> {
            String body = """
                <html><body><main><h1>Exchange JS search</h1><script>var sseQueryURL = "http://localhost:%d/"; var api = "search/getESSearchDoc.do";</script></main></body></html>
                """.formatted(server.getAddress().getPort());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/search/getESSearchDoc.do", exchange -> {
            siteSearchRawQuery = exchange.getRequestURI().getRawQuery();
            siteSearchKeyword = queryParam(exchange.getRequestURI().getRawQuery(), "keyword");
            String body = """
                jsonpCallback({"code":"0","data":{"totalSize":1,"knowledgeList":[{"documentId":"doc-603383","title":"[603383][Vertex Software] 2025 annual report summary","rtfContent":"Vertex Software disclosure summary, security code 603383","createTime":"2026-04-16 19:14:05","score":2262.8176,"extend":[{"name":"CURL","value":"/disclosure/603383_annual_summary.pdf"},{"name":"ZQDM","value":"603383"},{"name":"GSJC","value":"Vertex Software"}]}]}})
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/javascript; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private void startWebSearchApiWithOffDomainResultsAndKnownSearchTarget() throws IOException {
        searchEngineQuery = null;
        siteSearchKeyword = null;
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            searchEngineQuery = queryParam(exchange.getRequestURI().getRawQuery(), "q");
            String body = """
                <html><body><ol><li class="b_algo"><h2><a href="https://www.kimi.com/zh/">Kimi AI assistant</a></h2><div class="b_caption"><p>Kimi model release notes unrelated to exchange disclosures.</p></div></li></ol></body></html>
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/exchange-js", exchange -> {
            String body = """
                <html><body><main><h1>Exchange JS search</h1><script>var sseQueryURL = "http://localhost:%d/"; var api = "search/getESSearchDoc.do";</script></main></body></html>
                """.formatted(server.getAddress().getPort());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/search/getESSearchDoc.do", exchange -> {
            siteSearchKeyword = queryParam(exchange.getRequestURI().getRawQuery(), "keyword");
            String body = """
                jsonpCallback({"code":"0","data":{"totalSize":1,"knowledgeList":[{"documentId":"doc-603383","title":"[603383][Vertex Software] 2025 annual summary","rtfContent":"Vertex Software disclosure summary, security code 603383","createTime":"2026-04-16 19:14:05","score":2262.8176,"extend":[{"name":"CURL","value":"/disclosure/603383_annual_summary.pdf"},{"name":"ZQDM","value":"603383"},{"name":"GSJC","value":"Vertex Software"}]}]}})
                """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/javascript; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private String queryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            if (!name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                continue;
            }
            String value = equals < 0 ? "" : part.substring(equals + 1);
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return null;
    }
}
