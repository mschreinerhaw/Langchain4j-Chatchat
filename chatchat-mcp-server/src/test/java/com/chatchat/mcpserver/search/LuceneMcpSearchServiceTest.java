package com.chatchat.mcpserver.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneMcpSearchServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void searchesAssetsByLogicalNameWithDescriptorText() {
        LuceneMcpSearchService service = service();

        List<LuceneMcpSearchService.SearchHit> hits = service.searchAssets(
            List.of(
                new LuceneMcpSearchService.AssetDoc(
                    "ds-248",
                    "sql_datasource",
                    "248测试数据库",
                    "248测试数据库",
                    "db_query_mysql_248_test_db",
                    "DEV",
                    "mysql",
                    List.of("mysql", "248测试数据库"),
                    "asset_query"
                )
            ),
            new LuceneMcpSearchService.AssetSearchRequest(
                "sql_datasource",
                "248测试数据库 数据库状态分析",
                "DEV",
                "mysql",
                List.of(),
                10
            )
        );

        assertThat(hits).extracting(LuceneMcpSearchService.SearchHit::id).containsExactly("ds-248");
        assertThat(hits.get(0).reasons()).anySatisfy(reason -> assertThat(reason).contains("lucene_bm25"));
    }

    @Test
    void searchesTemplatesByDbTypeAndIntentText() {
        LuceneMcpSearchService service = service();

        List<LuceneMcpSearchService.SearchHit> hits = service.searchTemplates(
            List.of(
                new LuceneMcpSearchService.TemplateDoc(
                    "MYSQL_SHOW_STATUS",
                    "sql_datasource",
                    "MySQL status variables",
                    "Show MySQL server status counters for health and performance inspection.",
                    "maintenance_instance",
                    "mysql",
                    "db_status status health instance",
                    "LOW",
                    List.of("status", "health", "instance"),
                    "sql_template"
                ),
                new LuceneMcpSearchService.TemplateDoc(
                    "MYSQL_DATABASE_SIZE",
                    "sql_datasource",
                    "MySQL database size",
                    "Summarize database size by schema.",
                    "maintenance_storage",
                    "mysql",
                    "storage size space",
                    "LOW",
                    List.of("storage", "size", "space"),
                    "sql_template"
                )
            ),
            new LuceneMcpSearchService.TemplateSearchRequest(
                "sql_datasource",
                "mysql",
                "数据库状态分析 db_status status health",
                10
            )
        );

        assertThat(hits).extracting(LuceneMcpSearchService.SearchHit::id)
            .startsWith("MYSQL_SHOW_STATUS");
    }

    private LuceneMcpSearchService service() {
        LuceneSearchProperties properties = new LuceneSearchProperties();
        properties.setIndexDir(tempDir.toString());
        return new LuceneMcpSearchService(properties);
    }
}
