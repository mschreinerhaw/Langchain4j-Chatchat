package com.chatchat.mcpserver.search.engine;

import com.chatchat.common.security.InternalCredentialProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpPublishedEmbeddingCatalogTest {
    @Test
    void persistsPublishedModelsEncryptedAndRestoresDefaultOnRestart() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:mcp-model-catalog;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
        when(credentials.resolvedSecret()).thenReturn("test-only-mcp-secret");
        LuceneSearchProperties properties = new LuceneSearchProperties();
        McpPublishedEmbeddingCatalog catalog = new McpPublishedEmbeddingCatalog(jdbc, properties, credentials);
        catalog.run(null);
        catalog.synchronize(new McpPublishedEmbeddingCatalog.Snapshot(List.of(
            new McpPublishedEmbeddingCatalog.EmbeddingModel("small", "small-provider",
                "https://example.test/embeddings", 768, 30000, "small-secret", true, false),
            new McpPublishedEmbeddingCatalog.EmbeddingModel("large", "large-provider",
                "https://example.test/embeddings", 1024, 45000, "large-secret", true, true)
        ), "large"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mcp_published_embedding_model", Integer.class))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT api_key_cipher FROM mcp_published_embedding_model "
            + "WHERE name='large'", String.class)).startsWith("ENC(").doesNotContain("large-secret");
        assertThat(properties.getOpenSearch().getEmbedding().getModel()).isEqualTo("large-provider");

        LuceneSearchProperties restarted = new LuceneSearchProperties();
        new McpPublishedEmbeddingCatalog(jdbc, restarted, credentials).run(null);
        assertThat(restarted.getOpenSearch().getEmbedding().getModel()).isEqualTo("large-provider");
        assertThat(restarted.getOpenSearch().getEmbedding().getApiKey()).isEqualTo("large-secret");

        catalog.synchronize(new McpPublishedEmbeddingCatalog.Snapshot(List.of(), null));
        assertThat(properties.getOpenSearch().getEmbedding().isEnabled()).isFalse();
    }
}
