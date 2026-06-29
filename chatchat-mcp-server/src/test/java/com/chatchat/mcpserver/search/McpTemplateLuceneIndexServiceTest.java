package com.chatchat.mcpserver.search;

import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.ops.CommandTemplateConfig;
import com.chatchat.mcpserver.ops.CommandTemplateService;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.sql.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.SqlTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpTemplateLuceneIndexServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void indexesDefaultSystemTemplatesIntoLuceneOnRefresh() {
        LuceneMcpSearchService lucene = lucene();
        CommandTemplateService commandTemplateService = mock(CommandTemplateService.class);
        SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
        HttpEndpointConfigService httpEndpointConfigService = mock(HttpEndpointConfigService.class);
        DatabaseQueryConfigService databaseQueryConfigService = mock(DatabaseQueryConfigService.class);
        when(commandTemplateService.listEnabled()).thenReturn(List.of(commandTemplate()));
        when(sqlTemplateService.listEnabled()).thenReturn(List.of(sqlTemplate()));
        when(httpEndpointConfigService.listEnabled()).thenReturn(List.of());
        when(databaseQueryConfigService.listAll()).thenReturn(List.of());
        McpTemplateLuceneIndexService indexService = new McpTemplateLuceneIndexService(
            lucene,
            commandTemplateService,
            sqlTemplateService,
            httpEndpointConfigService,
            databaseQueryConfigService,
            new ObjectMapper()
        );

        indexService.refreshAll();

        assertThat(lucene.searchTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "ssh_host", null, "system overview memory disk", 10
        ))).extracting(LuceneMcpSearchService.SearchHit::id)
            .contains("CHECK_SYSTEM_OVERVIEW");
        assertThat(lucene.searchTemplates(new LuceneMcpSearchService.TemplateSearchRequest(
            "sql_datasource", "mysql", "database status health", 10
        ))).extracting(LuceneMcpSearchService.SearchHit::id)
            .contains("MYSQL_SHOW_STATUS");
    }

    private CommandTemplateConfig commandTemplate() {
        CommandTemplateConfig config = new CommandTemplateConfig();
        config.setCode("CHECK_SYSTEM_OVERVIEW");
        config.setTitle("System overview");
        config.setDescription("Read-only host load, memory, disk and process overview.");
        config.setCategory("system_diagnostic");
        config.setRiskLevel("LOW");
        config.setIntentSignalsJson("[\"system\",\"overview\",\"memory\",\"disk\"]");
        config.setEnabled(true);
        return config;
    }

    private SqlTemplateConfig sqlTemplate() {
        SqlTemplateConfig config = new SqlTemplateConfig();
        config.setCode("MYSQL_SHOW_STATUS");
        config.setTitle("MySQL status variables");
        config.setDescription("Show MySQL server status counters for health and performance inspection.");
        config.setDatabaseType("mysql");
        config.setCategory("maintenance_instance");
        config.setRiskLevel("LOW");
        config.setSqlTemplate("SHOW STATUS");
        config.setIntentSignalsJson("[\"db_status\",\"status\",\"health\",\"instance\"]");
        config.setEnabled(true);
        return config;
    }

    private LuceneMcpSearchService lucene() {
        LuceneSearchProperties properties = new LuceneSearchProperties();
        properties.setIndexDir(tempDir.toString());
        return new LuceneMcpSearchService(properties);
    }
}
