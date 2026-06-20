package com.chatchat.mcpserver.web;

import com.chatchat.tools.builtin.WebSearchToolProperties;
import com.chatchat.tools.web.WebCrawlerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class WebKnowledgeMcpToolRegistrarTest {

    @Test
    void financeSiteSeedUrlAllowsBlankKeyword() {
        WebKnowledgeMcpToolRegistrar registrar = newRegistrar();
        WebSearchToolProperties.FinanceSiteSearchTarget target = new WebSearchToolProperties.FinanceSiteSearchTarget();
        target.setSearchUrlTemplate("https://example.test/search?keyword={keyword}");

        String seedUrl = ReflectionTestUtils.invokeMethod(registrar, "financeSiteSeedUrl", target, "");

        assertThat(seedUrl).isEqualTo("https://example.test/search?keyword=");
    }

    @Test
    void financeSiteSeedUrlAllowsNullKeyword() {
        WebKnowledgeMcpToolRegistrar registrar = newRegistrar();
        WebSearchToolProperties.FinanceSiteSearchTarget target = new WebSearchToolProperties.FinanceSiteSearchTarget();
        target.setSearchUrlTemplate("https://example.test/search?keyword={keyword}");

        String seedUrl = ReflectionTestUtils.invokeMethod(registrar, "financeSiteSeedUrl", target, (String) null);

        assertThat(seedUrl).isEqualTo("https://example.test/search?keyword=");
    }

    @Test
    void searchTemplateToUrlAllowsBlankKeyword() {
        WebKnowledgeMcpToolRegistrar registrar = newRegistrar();

        String seedUrl = ReflectionTestUtils.invokeMethod(
            registrar,
            "searchTemplateToUrl",
            "https://example.test/search?q={q}",
            ""
        );

        assertThat(seedUrl).isEqualTo("https://example.test/search?q=");
    }

    private WebKnowledgeMcpToolRegistrar newRegistrar() {
        return new WebKnowledgeMcpToolRegistrar(
            null,
            null,
            null,
            null,
            new WebCrawlerProperties(),
            new WebSearchToolProperties(),
            new MockEnvironment()
        );
    }
}
