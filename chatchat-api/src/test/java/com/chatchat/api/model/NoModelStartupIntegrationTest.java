package com.chatchat.api.model;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Full API context and real catalog/HTTP publication; only provider calls are fixtures. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:no_model_startup;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "chatchat.api.auth.enabled=true", "chatchat.models.openai.api-key=",
    "chatchat.search.open-search.embedding.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext
@Import(NoModelStartupIntegrationTest.MigratedDatabase.class)
class NoModelStartupIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class MigratedDatabase {
        @Bean ApplicationRunner semanticPolicyMigration(JdbcTemplate jdbc) {
            return args -> new ResourceDatabasePopulator(new FileSystemResource(
                "../database/migration/h2/V20261009_01__agent_runtime_semantic_policy.sql"))
                .execute(jdbc.getDataSource());
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ModelsConfig properties;
    @Autowired ModelResourceRegistry resources;
    @Autowired PlatformModelCatalogService catalog;
    @Autowired SearchProperties search;
    @Autowired ChatModel chat;
    @Autowired EnterpriseAdminService identities;
    @MockBean ConfigurableChatModelFactory factory;

    @Test
    void startsEmptyThenAcceptsPageConfigurationWithoutRestart() throws Exception {
        assertThat(properties.getConfiguredChatModelKeys()).isEmpty();
        assertThat(properties.getDefaultChatModel()).isNull();
        assertThat(catalog.list()).isEmpty();
        assertThat(resources.selectableChatModels()).isEmpty();
        assertThat(search.getOpenSearch().getEmbedding().isEnabled()).isFalse();
        verifyNoInteractions(factory);
        assertThatThrownBy(() -> chat.chat("before configuration"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Default chat model is not configured");

        String token = identities.login("admin", "123456").token();
        mvc.perform(get("/api/v1/platform/models").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(put("/api/v1/platform/models").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("""
                    {"name":"page-model","type":"chat","baseUrl":"https://provider.example/v1",
                     "providerModel":"provider-model","enabled":true,"defaultModel":true}
                    """))
            .andExpect(status().isOk());
        assertThat(resources.selectableChatModels()).isEmpty();
        mvc.perform(post("/api/v1/platform/models/publish").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"type\":\"chat\",\"name\":\"page-model\"}"))
            .andExpect(status().isOk());
        assertThat(resources.defaultChatModel()).isEqualTo("page-model");
        assertThat(resources.require("page-model").providerModelName()).isEqualTo("provider-model");
        ChatModel provider = mock(ChatModel.class);
        when(factory.create("page-model")).thenReturn(provider);
        when(provider.chat("after publication")).thenReturn("configured");
        assertThat(chat.chat("after publication")).isEqualTo("configured");

        mvc.perform(delete("/api/v1/platform/models").header("Authorization", "Bearer " + token)
                .param("type", "chat").param("name", "page-model"))
            .andExpect(status().isOk());
        assertThat(resources.selectableChatModels()).isEmpty();
        assertThatThrownBy(() -> chat.chat("after deletion"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Default chat model is not configured");
        verify(factory, times(1)).create("page-model");
    }
}
