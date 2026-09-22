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
        jdbc.execute("CREATE TABLE platform_model_config ("
            + "model_type VARCHAR(24) NOT NULL,name VARCHAR(128) NOT NULL,"
            + "provider_model VARCHAR(128),base_url VARCHAR(1024) NOT NULL,protocol VARCHAR(40),"
            + "dimension INTEGER,timeout INTEGER,max_tokens INTEGER,max_retries INTEGER,api_key_cipher TEXT,"
            + "enabled BOOLEAN NOT NULL,is_default BOOLEAN NOT NULL,"
            + "source VARCHAR(24) NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(model_type,name))");
        PlatformModelCatalogService service = new PlatformModelCatalogService(jdbc, file, search,
            new MockEnvironment().withProperty("chatchat.models.crypto-key", "test-only-encryption-key"));
        ModelResourceRegistry registry = new ModelResourceRegistry(file);
        registry.setCatalog(service);

        assertThat(service.list()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT api_key_cipher FROM platform_model_config", String.class))
            .startsWith("ENC(").doesNotContain("file-secret");
        assertThat(service.list().toString()).doesNotContain("file-secret");

        service.save(new PlatformModelCatalogService.ModelDraft("new-chat", "通用问答", "适合日常问答与内容总结", "chat", "provider-chat",
            "https://user.example/v1", "openai", null, 180000, 4096, 2,
            "new-secret", true, true));
        assertThat(service.list().stream().filter(model -> "new-chat".equals(model.name())).findFirst().orElseThrow())
            .extracting(PlatformModelCatalogService.ModelView::alias, PlatformModelCatalogService.ModelView::description)
            .containsExactly("通用问答", "适合日常问答与内容总结");
        service.save(new PlatformModelCatalogService.ModelDraft("new-chat", "分析助手", "适合复杂任务分析", "chat", "provider-chat",
            "https://user.example/v1", "openai", null, 180000, 4096, 2,
            "", true, true));
        assertThat(service.list().stream().filter(model -> "new-chat".equals(model.name())).findFirst().orElseThrow())
            .extracting(PlatformModelCatalogService.ModelView::alias, PlatformModelCatalogService.ModelView::description)
            .containsExactly("分析助手", "适合复杂任务分析");
        assertThat(registry.defaultChatModel()).isEqualTo("new-chat");
        assertThat(registry.require("new-chat").config().getApiKey()).isEqualTo("new-secret");
        assertThat(registry.selectableChatModels()).contains("new-chat", "configured-chat");
        assertThat(service.list().toString()).doesNotContain("new-secret");

        service.save(new PlatformModelCatalogService.ModelDraft("vector-v2", null, null, "embedding", "vector-provider",
            "https://vector.example/v1/embeddings", "openai", 1536, 120000, null, null,
            "vector-secret", true, true));
        assertThat(search.getOpenSearch().getEmbedding().getModel()).isEqualTo("vector-provider");
        assertThat(search.getOpenSearch().getEmbedding().getDimension()).isEqualTo(1536);
        service.delete("embedding", "vector-v2");
        assertThat(search.getOpenSearch().getEmbedding().isEnabled()).isFalse();
    }
}
