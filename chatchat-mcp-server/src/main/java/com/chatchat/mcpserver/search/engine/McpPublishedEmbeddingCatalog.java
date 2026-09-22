package com.chatchat.mcpserver.search.engine;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalSecretCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MCP-owned persisted copy of the API's published embedding catalog. */
@Service
@RequiredArgsConstructor
public class McpPublishedEmbeddingCatalog implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final LuceneSearchProperties properties;
    private final InternalCredentialProperties credentials;

    public record EmbeddingModel(String name, String providerModel, String baseUrl, int dimension,
                                 Integer timeout, String apiKey, boolean enabled, boolean defaultModel) { }
    public record Snapshot(List<EmbeddingModel> models, String preferredDefault) { }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS mcp_published_embedding_model ("
            + "name VARCHAR(128) PRIMARY KEY,provider_model VARCHAR(128) NOT NULL,"
            + "base_url VARCHAR(1024) NOT NULL,dimension INTEGER NOT NULL,timeout INTEGER,"
            + "api_key_cipher TEXT,enabled BOOLEAN NOT NULL,is_default BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS mcp_embedding_catalog_state "
            + "(id INTEGER PRIMARY KEY,synced BOOLEAN NOT NULL)");
        Integer synced = jdbc.queryForObject(
            "SELECT COUNT(*) FROM mcp_embedding_catalog_state WHERE id=1 AND synced=TRUE", Integer.class);
        if (synced != null && synced > 0) applyFromStore();
    }

    @Transactional
    public synchronized void synchronize(Snapshot snapshot) {
        if (snapshot == null || snapshot.models() == null) {
            throw new IllegalArgumentException("Embedding snapshot is required");
        }
        Set<String> names = new HashSet<>();
        for (EmbeddingModel model : snapshot.models()) {
            if (model == null || model.name() == null || model.name().isBlank()
                || model.providerModel() == null || model.providerModel().isBlank()
                || model.dimension() <= 0 || !validUrl(model.baseUrl())
                || !names.add(model.name())) {
                throw new IllegalArgumentException("Invalid embedding model snapshot");
            }
        }
        jdbc.update("DELETE FROM mcp_published_embedding_model");
        String preferred = snapshot.preferredDefault();
        boolean hasPreferred = preferred != null && snapshot.models().stream()
            .anyMatch(model -> preferred.equals(model.name()) && model.enabled());
        for (EmbeddingModel model : snapshot.models()) {
            jdbc.update("INSERT INTO mcp_published_embedding_model "
                    + "(name,provider_model,base_url,dimension,timeout,api_key_cipher,enabled,is_default) "
                    + "VALUES (?,?,?,?,?,?,?,?)",
                model.name(), model.providerModel(), model.baseUrl(), model.dimension(), model.timeout(),
                model.apiKey() == null || model.apiKey().isBlank() ? null
                    : InternalSecretCipher.encrypt(model.apiKey(), credentials.resolvedSecret()),
                model.enabled(), hasPreferred ? preferred.equals(model.name()) : model.defaultModel());
        }
        jdbc.update("DELETE FROM mcp_embedding_catalog_state WHERE id=1");
        jdbc.update("INSERT INTO mcp_embedding_catalog_state (id,synced) VALUES (1,TRUE)");
        applyFromStore();
    }

    private void applyFromStore() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT provider_model,base_url,dimension,timeout,api_key_cipher "
                + "FROM mcp_published_embedding_model WHERE enabled=TRUE "
                + "ORDER BY is_default DESC,name ASC");
        LuceneSearchProperties.OpenSearch.Embedding embedding = properties.getOpenSearch().getEmbedding();
        if (rows.isEmpty()) {
            embedding.setEnabled(false);
            embedding.setModel("");
            embedding.setEndpoint("");
            embedding.setApiKey("");
            return;
        }
        Map<String, Object> row = rows.get(0);
        embedding.setModel((String) row.get("provider_model"));
        embedding.setEndpoint((String) row.get("base_url"));
        String cipher = (String) row.get("api_key_cipher");
        embedding.setApiKey(cipher == null ? "" : InternalSecretCipher.decryptIfNecessary(
            cipher, credentials.resolvedSecret()));
        embedding.setDimension(((Number) row.get("dimension")).intValue());
        if (row.get("timeout") != null) embedding.setRequestTimeoutMs(((Number) row.get("timeout")).intValue());
        embedding.setEnabled(true);
    }

    private boolean validUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
