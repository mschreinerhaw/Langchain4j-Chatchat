package com.chatchat.mcpserver.templatepublication.catalog;

import com.chatchat.mcpserver.python.PythonMcpToolPublisher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateQueryParentCatalogTest {
    @Test
    void queryParentsAcceptOnlyMatchingStudioFamiliesAndKeepLegacyOpsSeparate() {
        var catalog = new TemplateQueryParentCatalog();
        var studio = new TemplateAssetCatalogService.TemplateAsset("sql_datasource:STUDIO_A", "sql_datasource",
            "STUDIO_A", "Studio", "", "", "", "", java.util.Map.of()).withQueryFamily("relational");
        assertThat(catalog.require("database_query_template_query").accepts(studio)).isTrue();
        assertThat(catalog.require("trino_query_template_query").accepts(studio)).isFalse();
        assertThat(catalog.require("database_query_template_query").accepts(studio.withQueryFamily(null))).isFalse();
        assertThat(catalog.require("database_ops_template_search").accepts(studio.withQueryFamily(null))).isTrue();
    }

    @Test
    void exposesPythonAnalysisAsAParentTemplateQueryTool() {
        TemplateQueryParentCatalog.ParentTool parent = new TemplateQueryParentCatalog()
            .require(PythonMcpToolPublisher.ANALYSIS_RUN_TOOL);

        assertThat(parent.assetType()).isEqualTo(TemplateAssetCatalogService.PYTHON);
        assertThat(parent.title()).isEqualTo("Python 分析模板检索");
    }
}
