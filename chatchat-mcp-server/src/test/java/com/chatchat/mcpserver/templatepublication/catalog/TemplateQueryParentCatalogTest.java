package com.chatchat.mcpserver.templatepublication.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateQueryParentCatalogTest {

    private final TemplateQueryParentCatalog catalog = new TemplateQueryParentCatalog();

    @Test
    void exposesFixedParentQueriesWithOneCanonicalAssetTypeEach() {
        assertThat(catalog.list())
            .extracting(TemplateQueryParentCatalog.ParentTool::toolName)
            .containsExactly(
                "ssh_template_query",
                "database_ops_template_search",
                "http_endpoint_template_query",
                "database_query_template_query",
                "api_template_query");
        assertThat(catalog.list())
            .extracting(TemplateQueryParentCatalog.ParentTool::assetType)
            .containsExactly("ssh_host", "sql_datasource", "http_endpoint", "database_query", "api_service");
    }

    @Test
    void rejectsAnyParentOutsideTheFixedCatalog() {
        assertThatThrownBy(() -> catalog.require("user_supplied_template_query"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported parent");
    }
}
