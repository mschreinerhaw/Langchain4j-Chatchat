package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.retrieval.McpParamBindingResolver;

import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.search.LuceneSearchProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Release stress gate for model-generated abbreviation retrieval continuity. */
class ProductionAbbreviationRetrievalStressE2E {

    private static final int CANDIDATES_PER_KIND = 256;
    private static final int CONCURRENCY = 48;
    private static final String[] ENGLISH_WORDS = {
        "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Gamma", "Hotel", "India",
        "Juliet", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo",
        "Sierra", "Tango", "Uniform", "Victor", "Whiskey", "Xray", "Yankee", "Zulu"
    };
    private static final char[] CHINESE_CHARACTERS = {
        '安', '包', '仓', '东', '发', '高', '海', '金', '开', '林',
        '美', '南', '平', '清', '人', '上', '天', '文', '新', '元', '中'
    };
    private static final char[] CHINESE_INITIALS = {
        'a', 'b', 'c', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'm', 'n', 'p', 'q', 'r', 's', 't', 'w', 'x', 'y', 'z'
    };

    @TempDir
    Path tempDir;

    @Test
    void modelAliasesRemainIsolatedAcrossResolverAssetAndTemplateSearchUnderConcurrency() {
        LuceneSearchProperties properties = new LuceneSearchProperties();
        properties.setIndexDir(tempDir.toString());
        LuceneMcpSearchService search = new LuceneMcpSearchService(properties);
        McpParamBindingResolver resolver = new McpParamBindingResolver();

        List<LuceneMcpSearchService.AssetDoc> assets = new ArrayList<>();
        List<LuceneMcpSearchService.TemplateDoc> templates = new ArrayList<>();
        for (int index = 0; index < CANDIDATES_PER_KIND; index++) {
            assets.add(new LuceneMcpSearchService.AssetDoc(
                "asset-" + index, "api_service", chineseName(index), chineseName(index), null,
                "STRESS", null, List.of(), "stress_asset_registry"));
            templates.add(new LuceneMcpSearchService.TemplateDoc(
                "template-" + index, "api_service", englishName(index), "bounded stress candidate",
                "stress", "generic", englishName(index), "LOW", List.of(),
                "stress_template_registry"));
        }
        search.indexAssets("api_service", assets);
        search.indexTemplates(templates);

        ExecutorService callers = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
                var futures = IntStream.range(0, CANDIDATES_PER_KIND).mapToObj(index -> callers.submit(() -> {
                    verifyAssetPath(search, resolver, index);
                    verifyTemplatePath(search, resolver, index);
                    return index;
                })).toList();
                for (var future : futures) {
                    assertThat(future.get(75, TimeUnit.SECONDS)).isBetween(0, CANDIDATES_PER_KIND - 1);
                }
            });
        } finally {
            callers.shutdownNow();
        }

        assertThat(search.searchAssets(new LuceneMcpSearchService.AssetSearchRequest(
            "api_service", "qqqqqq", "STRESS", null, List.of(), 10))).isEmpty();
        assertThat(search.searchTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "api_service", null, "qqqqqq", 10))).isEmpty();
        assertThat(search.searchTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "api_service", null, "x".repeat(20_000), 10))).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void verifyAssetPath(LuceneMcpSearchService search, McpParamBindingResolver resolver, int index) {
        String name = chineseName(index);
        String alias = chineseAlias(index);
        Map<String, Object> resolved = resolver.resolve(
            "mcp_dynamic_api_asset_query", null,
            Map.of(
                "candidates", List.of(Map.of("targetKind", "api", "confidence", 0.97)),
                "finalDecision", "api",
                "filters", Map.of(
                    "intent", name,
                    "queryTerms", List.of(name, alias),
                    "keywords", List.of(alias)
                )
            ),
            "inspect " + name
        );
        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");
        assertThat((List<String>) filters.get("queryTerms")).contains(name, alias);
        assertThat(filters).doesNotContainKeys("assetName", "templateId");

        assertThat(search.searchAssets(new LuceneMcpSearchService.AssetSearchRequest(
            "api_service", alias, "STRESS", null, List.of(), 5)))
            .extracting(LuceneMcpSearchService.SearchHit::id)
            .containsExactly("asset-" + index);
    }

    @SuppressWarnings("unchecked")
    private void verifyTemplatePath(LuceneMcpSearchService search, McpParamBindingResolver resolver, int index) {
        String name = englishName(index);
        String alias = englishAlias(index);
        Map<String, Object> resolved = resolver.resolve(
            "mcp_dynamic_api_template_query", null,
            Map.of(
                "candidates", List.of(Map.of("targetKind", "api", "confidence", 0.97)),
                "finalDecision", "api",
                "filters", Map.of(
                    "intent", name,
                    "queryTerms", List.of(name, alias),
                    "keywords", List.of(alias)
                )
            ),
            "run " + name
        );
        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");
        assertThat((List<String>) filters.get("queryTerms")).contains(name, alias);
        assertThat(filters).doesNotContainKeys("assetName", "templateId");

        assertThat(search.searchTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "api_service", null, alias, 5)))
            .extracting(LuceneMcpSearchService.SearchHit::id)
            .containsExactly("template-" + index);
    }

    private String englishName(int index) {
        int radix = ENGLISH_WORDS.length;
        return ENGLISH_WORDS[(index / (radix * radix)) % radix] + " "
            + ENGLISH_WORDS[(index / radix) % radix] + " "
            + ENGLISH_WORDS[index % radix];
    }

    private String englishAlias(int index) {
        int radix = ENGLISH_WORDS.length;
        return "" + Character.toLowerCase(ENGLISH_WORDS[(index / (radix * radix)) % radix].charAt(0))
            + Character.toLowerCase(ENGLISH_WORDS[(index / radix) % radix].charAt(0))
            + Character.toLowerCase(ENGLISH_WORDS[index % radix].charAt(0));
    }

    private String chineseName(int index) {
        int radix = CHINESE_CHARACTERS.length;
        return "" + CHINESE_CHARACTERS[(index / (radix * radix)) % radix]
            + CHINESE_CHARACTERS[(index / radix) % radix]
            + CHINESE_CHARACTERS[index % radix];
    }

    private String chineseAlias(int index) {
        int radix = CHINESE_INITIALS.length;
        return "" + CHINESE_INITIALS[(index / (radix * radix)) % radix]
            + CHINESE_INITIALS[(index / radix) % radix]
            + CHINESE_INITIALS[index % radix];
    }
}
