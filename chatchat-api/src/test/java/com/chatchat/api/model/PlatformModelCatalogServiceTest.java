package com.chatchat.api.model;

import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformModelCatalogServiceTest {
    @Test
    void importsFileConfigurationEncryptedAndPrefersUserChangesWithoutExposingSecrets() {
        ModelsConfig file = new ModelsConfig();
        file.setDefaultChatModel("configured-chat");
        ModelsConfig.ModelConnectionConfig connection = new ModelsConfig.ModelConnectionConfig();
        connection.setBaseUrl("https://file.example/v1");
        connection.setApiKey("file-secret");
        file.setChatModels(Map.of("configured-chat", connection));
        SearchProperties search = new SearchProperties();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:model-catalog-test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        PlatformModelCatalogService service = new PlatformModelCatalogService(jdbc, file, search,
            new MockEnvironment().withProperty("chatchat.models.crypto-key", "test-only-encryption-key"));
        ModelResourceRegistry registry = new ModelResourceRegistry(file);
        registry.setCatalog(service);

        assertThat(service.list()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT api_key_cipher FROM platform_model_config", String.class))
            .startsWith("ENC(").doesNotContain("file-secret");
        assertThat(service.list().toString()).doesNotContain("file-secret");

        service.save(new PlatformModelCatalogService.ModelDraft("new-chat", "chat", "provider-chat",
            "https://user.example/v1", "openai", null, 180000, 4096, 2,
            "new-secret", true, true));
        assertThat(registry.defaultChatModel()).isEqualTo("new-chat");
        assertThat(registry.require("new-chat").config().getApiKey()).isEqualTo("new-secret");
        assertThat(registry.selectableChatModels()).contains("new-chat", "configured-chat");
        assertThat(service.list().toString()).doesNotContain("new-secret");

        service.save(new PlatformModelCatalogService.ModelDraft("vector-v2", "embedding", "vector-provider",
            "https://vector.example/v1/embeddings", "openai", 1536, 120000, null, null,
            "vector-secret", true, true));
        assertThat(search.getOpenSearch().getEmbedding().getModel()).isEqualTo("vector-provider");
        assertThat(search.getOpenSearch().getEmbedding().getDimension()).isEqualTo(1536);
        service.delete("embedding", "vector-v2");
        assertThat(search.getOpenSearch().getEmbedding().isEnabled()).isFalse();
    }
}
