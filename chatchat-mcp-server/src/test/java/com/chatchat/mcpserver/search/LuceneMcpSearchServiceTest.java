package com.chatchat.mcpserver.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneMcpSearchServiceTest {

    @Test
    void assetIndexesAreIsolatedByAssetType() {
        LuceneMcpSearchService searchService = new LuceneMcpSearchService();
        searchService.replaceAssets(List.of(
            new LuceneMcpSearchService.AssetDoc("ssh-prod", "ssh_host", "prod", "prod", "ssh_prod", "prod", null, List.of("service:order"), "ssh_asset_query"),
            new LuceneMcpSearchService.AssetDoc("db-prod", "sql_datasource", "prod", "prod", "db_query_prod", "prod", "mysql", List.of("service:order"), "sql_datasource_asset_query")
        ));

        List<LuceneMcpSearchService.SearchHit> sshHits = searchService.searchAssets(
            new LuceneMcpSearchService.AssetSearchRequest("ssh_host", "prod", "prod", null, List.of("order"), 10)
        );
        List<LuceneMcpSearchService.SearchHit> dbHits = searchService.searchAssets(
            new LuceneMcpSearchService.AssetSearchRequest("sql_datasource", "prod", "prod", "mysql", List.of("order"), 10)
        );

        assertThat(sshHits).extracting(LuceneMcpSearchService.SearchHit::id).containsExactly("ssh-prod");
        assertThat(dbHits).extracting(LuceneMcpSearchService.SearchHit::id).containsExactly("db-prod");
    }

    @Test
    void templateIndexesAreIsolatedByAssetType() {
        LuceneMcpSearchService searchService = new LuceneMcpSearchService();
        searchService.replaceTemplates(List.of(
            new LuceneMcpSearchService.TemplateDoc("CHECK_STATUS", "ssh_host", "Check status", "host status", "diagnostic", null, "status", "LOW", List.of("status"), "ssh_template_query"),
            new LuceneMcpSearchService.TemplateDoc("MYSQL_STATUS", "sql_datasource", "MySQL status", "database status", "maintenance", "mysql", "status", "LOW", List.of("status"), "sql_datasource_template_query")
        ));

        List<LuceneMcpSearchService.SearchHit> sshHits = searchService.searchTemplates(List.of(),
            new LuceneMcpSearchService.TemplateSearchRequest("ssh_host", null, "status", 10)
        );
        List<LuceneMcpSearchService.SearchHit> dbHits = searchService.searchTemplates(List.of(),
            new LuceneMcpSearchService.TemplateSearchRequest("sql_datasource", "mysql", "status", 10)
        );

        assertThat(sshHits).extracting(LuceneMcpSearchService.SearchHit::id).containsExactly("CHECK_STATUS");
        assertThat(dbHits).extracting(LuceneMcpSearchService.SearchHit::id).containsExactly("MYSQL_STATUS");
    }
}
