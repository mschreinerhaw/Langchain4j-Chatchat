package com.chatchat.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentPropertiesNamingTest {
    private static final Pattern ACTIVE_ASSIGNMENT =
        Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=.*$");

    @Test
    void envPropertiesNamesResolveToDevYamlProperties() {
        SystemEnvironmentPropertySource source = new SystemEnvironmentPropertySource("test-env", Map.of(
            "CHATCHAT_MODELS_CONTEXT_WINDOW_MAX_TOKENS", "210000",
            "CHATCHAT_AGENT_RUNTIME_FINAL_SUMMARY_WEB_SEARCH_ENABLED", "false",
            "CHATCHAT_SEARCH_OPENSEARCH_MAX_QUERY_TERMS", "25",
            "CHATCHAT_CHAT_DETAIL_STORE_PATH", "./data/custom-chat",
            "CHATCHAT_MCP_NEWS_RUNTIME_BASE_URL", "http://news:8091",
            "CHATCHAT_MCP_LUCENE_OPEN_SEARCH_SEARCH_CONCURRENCY_REQUEST_TIMEOUT_MS", "9000",
            "CHATCHAT_LICENSE_LICENSE_FILE", "./license/custom.dat"
        ));

        assertThat(source.getProperty("chatchat.models.context-window-max-tokens")).isEqualTo("210000");
        assertThat(source.getProperty("chatchat.agent-runtime.final-summary-web-search-enabled"))
            .isEqualTo("false");
        assertThat(source.getProperty("chatchat.search.opensearch.max-query-terms")).isEqualTo("25");
        assertThat(source.getProperty("chatchat.chat.detail-store.path")).isEqualTo("./data/custom-chat");
        assertThat(source.getProperty("chatchat.mcp.news-runtime.base-url")).isEqualTo("http://news:8091");
        assertThat(source.getProperty(
            "chatchat.mcp.lucene.open-search.search-concurrency.request-timeout-ms")).isEqualTo("9000");
        assertThat(source.getProperty("chatchat.license.license-file")).isEqualTo("./license/custom.dat");
    }

    @Test
    void environmentFilesContainOnlyJvmOptions() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<Path> templates = List.of(
            repositoryRoot.resolve("packaging/config/env.properties"),
            repositoryRoot.resolve("chatchat-mcp-server/src/main/distribution/config/env.properties"),
            repositoryRoot.resolve("chatchat-runtime-news/src/main/distribution/config/env.properties")
        );

        for (Path template : templates) {
            List<String> lines = Files.readAllLines(template, StandardCharsets.UTF_8);
            List<String> keys = lines.stream()
                .map(ACTIVE_ASSIGNMENT::matcher)
                .filter(matcher -> matcher.matches())
                .map(matcher -> matcher.group(1))
                .toList();
            assertThat(keys)
                .describedAs("%s may configure JVM options only", template)
                .containsExactly("JAVA_OPTS");
        }
    }

    @Test
    void environmentLoadersAcceptOnlyJvmOptions() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<Path> loaders = List.of(
            repositoryRoot.resolve("packaging/bin/load-env.sh"),
            repositoryRoot.resolve("packaging/bin/load-env.ps1"),
            repositoryRoot.resolve("chatchat-mcp-server/src/main/scripts/load-env.sh"),
            repositoryRoot.resolve("chatchat-mcp-server/src/main/scripts/load-env.ps1"),
            repositoryRoot.resolve("chatchat-runtime-news/src/main/scripts/load-env.sh"),
            repositoryRoot.resolve("chatchat-runtime-news/src/main/scripts/load-env.ps1")
        );

        for (Path loader : loaders) {
            String content = Files.readString(loader, StandardCharsets.UTF_8);
            assertThat(content).contains("JAVA_OPTS");
            assertThat(content)
                .doesNotContain("env.local", "APP_ARGS", "SPRING_", "CHATCHAT_", "SERVER_");
        }
    }

    private Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("chatchat-api"))
                && Files.isDirectory(current.resolve("chatchat-mcp-server"))
                && Files.isDirectory(current.resolve("chatchat-runtime-news"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root from " + System.getProperty("user.dir"));
    }
}
