package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import com.chatchat.mcpserver.search.LuceneSearchProperties;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.SqlTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandTemplateDiscoveryLuceneIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void templateQueryUsesLuceneRecallBeforeExistingProtocolResponse() {
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        SqlDatasourceConfigService datasourceService = mock(SqlDatasourceConfigService.class);
        CommandTemplateDiscoveryService service = new CommandTemplateDiscoveryService(
            mock(CommandTemplateService.class),
            mock(SshHostConfigService.class),
            sqlTemplateService,
            datasourceService,
            mock(HttpEndpointConfigService.class),
            new ObjectMapper(),
            new TemplateDiscoveryProperties(),
            lucene()
        );
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-248");
        datasource.setName("\u0032\u0034\u0038\u6d4b\u8bd5\u6570\u636e\u5e93");
        datasource.setTitle("\u0032\u0034\u0038\u6d4b\u8bd5\u6570\u636e\u5e93");
        datasource.setToolName("db_query_mysql_248_test_db");
        datasource.setEnvironment("DEV");
        datasource.setDatabaseType("mysql");
        datasource.setAllowedTemplatesJson("[\"MYSQL_SHOW_STATUS\",\"MYSQL_DATABASE_SIZE\"]");
        when(datasourceService.listEnabled()).thenReturn(List.of(datasource));
        when(sqlTemplateService.listEnabled()).thenReturn(List.of(
            sqlTemplate(
                "MYSQL_DATABASE_SIZE",
                "MySQL database size",
                "Summarize MySQL database size by schema.",
                "maintenance_storage",
                "[\"storage\",\"size\",\"space\"]"
            ),
            sqlTemplate(
                "MYSQL_SHOW_STATUS",
                "MySQL status variables",
                "Show MySQL server status counters for health and performance inspection.",
                "maintenance_instance",
                "[\"db_status\",\"status\",\"health\",\"instance\"]"
            )
        ));

        Map<String, Object> result = service.query(Map.of(
            "targetKind", "database",
            "confidence", 0.9,
            "filters", Map.of(
                "assetName", "\u0032\u0034\u0038\u6d4b\u8bd5\u6570\u636e\u5e93 \u6570\u636e\u5e93\u72b6\u6001\u5206\u6790",
                "env", "DEV",
                "intent", "\u6570\u636e\u5e93\u72b6\u6001\u5206\u6790"
            ),
            "trace", trace()
        ));

        List<?> templates = (List<?>) result.get("templates");
        Map<?, ?> first = (Map<?, ?>) templates.get(0);
        assertThat((Integer) result.get("returnedCount")).isGreaterThanOrEqualTo(1);
        assertThat(first.get("templateId")).isEqualTo("MYSQL_SHOW_STATUS");
        assertThat(first.get("matchReasons").toString()).contains("lucene template index matched bm25");
        assertThat(result.get("resolutionTrace").toString())
            .contains("registry_universe_with_lucene_score_then_authorized_feature_rank", "lucene_scored");
    }

    private SqlTemplateConfig sqlTemplate(String code,
                                          String title,
                                          String description,
                                          String category,
                                          String signals) {
        SqlTemplateConfig template = new SqlTemplateConfig();
        template.setCode(code);
        template.setTitle(title);
        template.setDescription(description);
        template.setCategory(category);
        template.setDatabaseType("mysql");
        template.setRiskLevel("LOW");
        template.setIntentSignalsJson(signals);
        template.setSqlTemplate("SHOW STATUS");
        template.setEnabled(true);
        return template;
    }

    private LuceneMcpSearchService lucene() {
        LuceneSearchProperties properties = new LuceneSearchProperties();
        properties.setIndexDir(tempDir.toString());
        return new LuceneMcpSearchService(properties);
    }

    private Map<String, Object> trace() {
        return Map.of("plannerVersion", "v1.0", "model", "unit-test");
    }
}
