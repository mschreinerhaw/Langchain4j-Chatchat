package com.chatchat.api.model;

import com.chatchat.common.config.ModelCatalogOverride;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.security.InternalSecretCipher;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Database-backed model catalog. Secrets are encrypted at rest and never appear in views. */
@Service
@RequiredArgsConstructor
public class PlatformModelCatalogService implements ModelCatalogOverride, ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final ModelsConfig fileConfig;
    private final SearchProperties searchProperties;
    private final Environment environment;
    private final McpModelSyncClient mcpSync;
    private volatile boolean initialized;

    @Override public synchronized void run(ApplicationArguments args) { initialize(); }

    public record ModelView(String name, String alias, String description, String type, String providerModel, String baseUrl,
                            String protocol, Integer dimension, Integer timeout, Integer maxTokens,
                            Integer maxRetries, boolean enabled, boolean defaultModel,
                            boolean hasApiKey, String source, long updatedAt,
                            boolean published, boolean pendingChanges,
                            boolean activeEnabled, boolean activeDefault) { }
    public record ModelDraft(String name, String alias, String description, String type, String providerModel, String baseUrl,
                             String protocol, Integer dimension, Integer timeout, Integer maxTokens,
                             Integer maxRetries, String apiKey, Boolean enabled,
                             Boolean defaultModel) { }

    public synchronized List<ModelView> list() {
        initialize();
        return jdbc.query("SELECT name,alias,description,model_type,provider_model,base_url,protocol,dimension,timeout,"
                + "max_tokens,max_retries,"
                + "enabled,is_default,api_key_cipher,source,updated_at,"
                + "(SELECT COUNT(*) FROM platform_model_publication p WHERE p.model_type=platform_model_config.model_type"
                + " AND p.name=platform_model_config.name) AS publication_count,"
                + "(SELECT p.published_at FROM platform_model_publication p WHERE p.model_type=platform_model_config.model_type"
                + " AND p.name=platform_model_config.name) AS published_at,"
                + "(SELECT p.enabled FROM platform_model_publication p WHERE p.model_type=platform_model_config.model_type"
                + " AND p.name=platform_model_config.name) AS active_enabled,"
                + "(SELECT p.is_default FROM platform_model_publication p WHERE p.model_type=platform_model_config.model_type"
                + " AND p.name=platform_model_config.name) AS active_default FROM platform_model_config "
                + "ORDER BY model_type,is_default DESC,updated_at DESC",
            (rs, row) -> new ModelView(rs.getString("name"), rs.getString("alias"),
                rs.getString("description"), rs.getString("model_type"),
                rs.getString("provider_model"), rs.getString("base_url"), rs.getString("protocol"),
                (Integer) rs.getObject("dimension"), (Integer) rs.getObject("timeout"),
                (Integer) rs.getObject("max_tokens"), (Integer) rs.getObject("max_retries"),
                rs.getBoolean("enabled"),
                rs.getBoolean("is_default"), rs.getString("api_key_cipher") != null,
                rs.getString("source"), rs.getLong("updated_at"),
                rs.getInt("publication_count") > 0,
                rs.getObject("published_at") != null && rs.getLong("updated_at") > rs.getLong("published_at"),
                rs.getBoolean("active_enabled"), rs.getBoolean("active_default")));
    }

    @Transactional
    public synchronized ModelView save(ModelDraft draft) {
        initialize();
        String name = required(draft == null ? null : draft.name(), "name");
        String type = normalizeType(draft.type());
        String url = required(draft.baseUrl(), "baseUrl");
        validateUrl(url);
        if ("embedding".equals(type) && (draft.dimension() == null || draft.dimension() <= 0)) {
            throw new IllegalArgumentException("Embedding dimension must be positive");
        }
        Integer dimension = "embedding".equals(type) ? draft.dimension() : null;
        String oldCipher = jdbc.query("SELECT api_key_cipher FROM platform_model_config WHERE model_type=? AND name=?",
            rs -> rs.next() ? rs.getString(1) : null, type, name);
        String cipher = draft.apiKey() == null || draft.apiKey().isBlank()
            ? oldCipher : InternalSecretCipher.encrypt(draft.apiKey().trim(), cryptoKey());
        boolean makeDefault = Boolean.TRUE.equals(draft.defaultModel());
        if (makeDefault && Boolean.FALSE.equals(draft.enabled())) {
            throw new IllegalArgumentException("Disabled model cannot be the default");
        }
        long now = System.currentTimeMillis();
        Long publishedAt = jdbc.queryForObject("SELECT MAX(published_at) FROM platform_model_publication "
            + "WHERE model_type=?", Long.class, type);
        if (publishedAt != null) now = Math.max(now, publishedAt + 1);
        if (makeDefault) jdbc.update("UPDATE platform_model_config SET is_default=FALSE,"
            + "source='user',updated_at=? WHERE model_type=? AND is_default=TRUE AND name<>?", now, type, name);
        int updated = jdbc.update("UPDATE platform_model_config SET alias=?,description=?,provider_model=?,base_url=?,protocol=?,"
                + "dimension=?,timeout=?,max_tokens=?,max_retries=?,api_key_cipher=?,enabled=?,is_default=?,"
                + "source='user',updated_at=? "
                + "WHERE model_type=? AND name=?",
            optionalText(draft.alias()), optionalText(draft.description()),
            text(draft.providerModel(), name), url, text(draft.protocol(), "auto"), dimension,
            draft.timeout(), draft.maxTokens(), draft.maxRetries(),
            cipher, !Boolean.FALSE.equals(draft.enabled()), makeDefault, now, type, name);
        if (updated == 0) {
            jdbc.update("INSERT INTO platform_model_config (model_type,name,alias,description,provider_model,base_url,protocol,"
                    + "dimension,timeout,max_tokens,max_retries,api_key_cipher,enabled,is_default,source,updated_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                type, name, optionalText(draft.alias()), optionalText(draft.description()),
                text(draft.providerModel(), name), url, text(draft.protocol(), "auto"),
                dimension, draft.timeout(), draft.maxTokens(), draft.maxRetries(), cipher,
                !Boolean.FALSE.equals(draft.enabled()), makeDefault,
                "user", now);
        }
        return list().stream().filter(row -> type.equals(row.type()) && name.equals(row.name()))
            .findFirst().orElseThrow();
    }

    @Transactional
    public synchronized ModelView publish(String type, String name) {
        initialize();
        type = normalizeType(type);
        List<Map<String, Object>> drafts = jdbc.queryForList(
            "SELECT * FROM platform_model_config WHERE model_type=? AND name=?", type, name);
        if (drafts.isEmpty()) throw new IllegalArgumentException("Model not found: " + name);
        Map<String, Object> draft = drafts.get(0);
        Integer activeDefaultCount = jdbc.queryForObject("SELECT COUNT(*) FROM platform_model_publication "
            + "WHERE model_type=? AND enabled=TRUE AND is_default=TRUE", Integer.class, type);
        if (Boolean.TRUE.equals(draft.get("enabled")) && (activeDefaultCount == null || activeDefaultCount == 0)) {
            jdbc.update("UPDATE platform_model_config SET is_default=TRUE WHERE model_type=? AND name=?", type, name);
            draft = jdbc.queryForMap("SELECT * FROM platform_model_config WHERE model_type=? AND name=?", type, name);
        }
        if ("embedding".equals(type)) {
            List<Map<String, Object>> candidate = jdbc.queryForList(
                "SELECT name,provider_model,base_url,dimension,timeout,api_key_cipher,enabled,is_default "
                    + "FROM platform_model_publication WHERE model_type='embedding' AND name<>?", name);
            candidate.add(draft);
            mcpSync.synchronize(candidate.stream().map(row -> new McpModelSyncClient.EmbeddingModel(
                (String) row.get("name"), (String) row.get("provider_model"),
                (String) row.get("base_url"), ((Number) row.get("dimension")).intValue(),
                row.get("timeout") == null ? null : ((Number) row.get("timeout")).intValue(),
                decrypt((String) row.get("api_key_cipher")),
                Boolean.TRUE.equals(row.get("enabled")),
                Boolean.TRUE.equals(row.get("is_default")))).toList(),
                Boolean.TRUE.equals(draft.get("is_default")) ? name : null);
        }
        if (Boolean.TRUE.equals(draft.get("is_default"))) {
            jdbc.update("UPDATE platform_model_publication SET is_default=FALSE WHERE model_type=?", type);
        }
        jdbc.update("DELETE FROM platform_model_publication WHERE model_type=? AND name=?", type, name);
        jdbc.update("INSERT INTO platform_model_publication "
                + "(model_type,name,provider_model,base_url,protocol,dimension,timeout,max_tokens,max_retries,"
                + "api_key_cipher,enabled,is_default,source,published_at) "
                + "SELECT model_type,name,provider_model,base_url,protocol,dimension,timeout,max_tokens,max_retries,"
                + "api_key_cipher,enabled,is_default,source,? FROM platform_model_config WHERE model_type=? AND name=?",
            System.currentTimeMillis(), type, name);
        ensureDefault(type);
        applyEmbedding();
        String publishedType = type;
        return list().stream().filter(row -> publishedType.equals(row.type()) && name.equals(row.name()))
            .findFirst().orElseThrow();
    }

    @Transactional
    public synchronized void setDefault(String type, String name) {
        initialize();
        type = normalizeType(type);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM platform_model_config WHERE model_type=? "
            + "AND name=? AND enabled=TRUE", Integer.class, type, name);
        if (exists == null || exists == 0) throw new IllegalArgumentException("Enabled model not found: " + name);
        long now = System.currentTimeMillis();
        Long publishedAt = jdbc.queryForObject("SELECT MAX(published_at) FROM platform_model_publication "
            + "WHERE model_type=?", Long.class, type);
        if (publishedAt != null) now = Math.max(now, publishedAt + 1);
        jdbc.update("UPDATE platform_model_config SET is_default=FALSE,source='user',updated_at=? "
            + "WHERE model_type=? AND is_default=TRUE AND name<>?", now, type, name);
        int changed = jdbc.update("UPDATE platform_model_config SET is_default=TRUE,source='user',updated_at=? "
            + "WHERE model_type=? AND name=? AND enabled=TRUE", now, type, name);
        if (changed == 0) throw new IllegalStateException("Default model update failed");
        // The selected default becomes active only when its draft is published.
    }

    @Transactional
    public synchronized void delete(String type, String name) {
        initialize();
        type = normalizeType(type);
        if ("embedding".equals(type)) {
            Integer published = jdbc.queryForObject("SELECT COUNT(*) FROM platform_model_publication "
                + "WHERE model_type=? AND name=?", Integer.class, type, name);
            if (published != null && published > 0) {
                List<McpModelSyncClient.EmbeddingModel> remaining = jdbc.query(
                    "SELECT name,provider_model,base_url,dimension,timeout,api_key_cipher,enabled,is_default "
                        + "FROM platform_model_publication WHERE model_type='embedding' AND name<>?",
                    (rs, row) -> new McpModelSyncClient.EmbeddingModel(
                        rs.getString("name"), rs.getString("provider_model"), rs.getString("base_url"),
                        rs.getInt("dimension"), (Integer) rs.getObject("timeout"),
                        decrypt(rs.getString("api_key_cipher")), rs.getBoolean("enabled"),
                        rs.getBoolean("is_default")), name);
                mcpSync.synchronize(remaining, null);
            }
        }
        jdbc.update("DELETE FROM platform_model_config WHERE model_type=? AND name=?", type, name);
        jdbc.update("DELETE FROM platform_model_publication WHERE model_type=? AND name=?", type, name);
        ensureDefault(type);
        applyEmbedding();
    }

    @Override public synchronized String defaultChatModel() {
        initialize();
        List<String> names = jdbc.query("SELECT name FROM platform_model_publication WHERE model_type='chat' "
            + "AND enabled=TRUE ORDER BY is_default DESC,published_at DESC", (rs, row) -> rs.getString(1));
        return names.isEmpty() ? null : names.get(0);
    }

    @Override public synchronized boolean hasChatModels() {
        initialize();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_model_publication WHERE model_type='chat'",
            Integer.class);
        return count != null && count > 0;
    }

    @Override public synchronized List<String> chatModelNames() {
        initialize();
        return jdbc.query("SELECT name FROM platform_model_publication WHERE model_type='chat' AND enabled=TRUE "
            + "ORDER BY is_default DESC,published_at DESC", (rs, row) -> rs.getString(1));
    }

    @Override public synchronized ModelsConfig.ResolvedModelConnection resolveChatModel(String name) {
        initialize();
        List<ModelsConfig.ResolvedModelConnection> matches = jdbc.query(
            "SELECT name,provider_model,base_url,protocol,timeout,max_tokens,max_retries,api_key_cipher "
                + "FROM platform_model_publication "
                + "WHERE model_type='chat' AND enabled=TRUE AND (LOWER(name)=LOWER(?) "
                + "OR LOWER(provider_model)=LOWER(?)) ORDER BY published_at DESC",
            (rs, row) -> {
                ModelsConfig.ModelConnectionConfig config = new ModelsConfig.ModelConnectionConfig();
                config.setModelName(rs.getString("provider_model"));
                config.setBaseUrl(rs.getString("base_url"));
                config.setProtocol(rs.getString("protocol"));
                if (rs.getObject("timeout") != null) config.setTimeout(rs.getInt("timeout"));
                if (rs.getObject("max_tokens") != null) config.setMaxTokens(rs.getInt("max_tokens"));
                if (rs.getObject("max_retries") != null) config.setMaxRetries(rs.getInt("max_retries"));
                config.setApiKey(decrypt(rs.getString("api_key_cipher")));
                ModelsConfig.ResolvedModelConnection fileMatch =
                    fileConfig.resolveChatModelConnection(rs.getString("name"));
                if (fileMatch != null && fileMatch.config() != null) {
                    config.setProxy(fileMatch.config().getProxy());
                }
                return new ModelsConfig.ResolvedModelConnection(name, rs.getString("name"), config,
                    ModelsConfig.ModelMatchType.CONFIG_KEY);
            }, name, name);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private void initialize() {
        if (initialized) return;
        jdbc.execute("CREATE TABLE IF NOT EXISTS platform_model_config ("
            + "model_type VARCHAR(24) NOT NULL,name VARCHAR(128) NOT NULL,"
            + "alias VARCHAR(128),description TEXT,"
            + "provider_model VARCHAR(128),base_url VARCHAR(1024) NOT NULL,protocol VARCHAR(40),"
            + "dimension INTEGER,timeout INTEGER,max_tokens INTEGER,max_retries INTEGER,api_key_cipher TEXT,"
            + "enabled BOOLEAN NOT NULL,is_default BOOLEAN NOT NULL,"
            + "source VARCHAR(24) NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(model_type,name))");
        addColumnIfMissing("alias", "VARCHAR(128)");
        addColumnIfMissing("description", "TEXT");
        jdbc.execute("CREATE TABLE IF NOT EXISTS platform_model_publication ("
            + "model_type VARCHAR(24) NOT NULL,name VARCHAR(128) NOT NULL,"
            + "provider_model VARCHAR(128),base_url VARCHAR(1024) NOT NULL,protocol VARCHAR(40),"
            + "dimension INTEGER,timeout INTEGER,max_tokens INTEGER,max_retries INTEGER,api_key_cipher TEXT,"
            + "enabled BOOLEAN NOT NULL,is_default BOOLEAN NOT NULL,source VARCHAR(24) NOT NULL,"
            + "published_at BIGINT NOT NULL,PRIMARY KEY(model_type,name))");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_model_config", Integer.class);
        if (count != null && count == 0) importFileConfig();
        jdbc.update("INSERT INTO platform_model_publication "
                + "(model_type,name,provider_model,base_url,protocol,dimension,timeout,max_tokens,max_retries,"
                + "api_key_cipher,enabled,is_default,source,published_at) "
                + "SELECT c.model_type,c.name,c.provider_model,c.base_url,c.protocol,c.dimension,c.timeout,"
                + "c.max_tokens,c.max_retries,c.api_key_cipher,c.enabled,c.is_default,c.source,c.updated_at "
                + "FROM platform_model_config c WHERE c.source='file' AND NOT EXISTS "
                + "(SELECT 1 FROM platform_model_publication p WHERE p.model_type=c.model_type AND p.name=c.name)");
        initialized = true;
        applyEmbedding();
    }

    private void importFileConfig() {
        long now = System.currentTimeMillis();
        List<String> keys = new ArrayList<>(fileConfig.getConfiguredChatModelKeys());
        if (keys.isEmpty() && fileConfig.getDefaultChatModel() != null) {
            keys.add(fileConfig.getDefaultChatModel());
        }
        SearchProperties.OpenSearch openSearch = searchProperties.getOpenSearch();
        SearchProperties.OpenSearch.Embedding embedding = openSearch == null ? null : openSearch.getEmbedding();
        boolean hasSecret = keys.stream().map(fileConfig::resolveChatModelConnection)
            .anyMatch(item -> item != null && item.config() != null && item.config().getApiKey() != null
                && !item.config().getApiKey().isBlank());
        if (hasSecret || embedding != null && embedding.getApiKey() != null
            && !embedding.getApiKey().isBlank()) cryptoKey();
        for (String name : keys) {
            ModelsConfig.ResolvedModelConnection resolved = fileConfig.resolveChatModelConnection(name);
            if (resolved == null || resolved.config() == null || resolved.config().getBaseUrl() == null
                || resolved.config().getBaseUrl().isBlank()) continue;
            ModelsConfig.ModelConnectionConfig config = resolved.config();
            jdbc.update("INSERT INTO platform_model_config (model_type,name,provider_model,base_url,protocol,"
                    + "dimension,timeout,max_tokens,max_retries,api_key_cipher,enabled,is_default,source,updated_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "chat", name, text(config.getModelName(), name), config.getBaseUrl(), config.getProtocol(),
                null, config.getTimeout(), config.getMaxTokens(), config.getMaxRetries(),
                encryptIfPresent(config.getApiKey()), true,
                name.equals(fileConfig.getDefaultChatModel()), "file", now);
        }
        if (embedding != null && embedding.getModel() != null && !embedding.getModel().isBlank()
            && embedding.getEndpoint() != null && !embedding.getEndpoint().isBlank()) {
            jdbc.update("INSERT INTO platform_model_config (model_type,name,provider_model,base_url,protocol,"
                    + "dimension,timeout,max_tokens,max_retries,api_key_cipher,enabled,is_default,source,updated_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "embedding", embedding.getModel(), embedding.getModel(), embedding.getEndpoint(), "openai",
                embedding.getDimension(), embedding.getRequestTimeoutMs(), null, null,
                encryptIfPresent(embedding.getApiKey()), embedding.isEnabled(),
                true, "file", now);
        }
    }

    private void applyEmbedding() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT provider_model,base_url,api_key_cipher,dimension,timeout "
            + "FROM platform_model_publication WHERE model_type='embedding' AND enabled=TRUE "
            + "ORDER BY is_default DESC,published_at DESC");
        if (searchProperties.getOpenSearch() == null) return;
        if (rows.isEmpty()) {
            searchProperties.getOpenSearch().getEmbedding().setEnabled(false);
            return;
        }
        Map<String, Object> row = rows.get(0);
        SearchProperties.OpenSearch.Embedding embedding = searchProperties.getOpenSearch().getEmbedding();
        embedding.setModel((String) row.get("provider_model"));
        embedding.setEndpoint((String) row.get("base_url"));
        embedding.setApiKey(decrypt((String) row.get("api_key_cipher")));
        embedding.setDimension(((Number) row.get("dimension")).intValue());
        if (row.get("timeout") != null) embedding.setRequestTimeoutMs(((Number) row.get("timeout")).intValue());
        embedding.setEnabled(true);
    }

    private void ensureDefault(String type) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_model_publication WHERE model_type=? "
            + "AND enabled=TRUE AND is_default=TRUE", Integer.class, type);
        if (count != null && count > 0) return;
        List<String> names = jdbc.query("SELECT name FROM platform_model_publication WHERE model_type=? AND enabled=TRUE "
            + "ORDER BY name ASC", (rs, row) -> rs.getString(1), type);
        if (!names.isEmpty()) jdbc.update("UPDATE platform_model_publication SET is_default=TRUE "
            + "WHERE model_type=? AND name=?", type, names.get(0));
    }

    private String encryptIfPresent(String value) {
        return value == null || value.isBlank() ? null : InternalSecretCipher.encrypt(value, cryptoKey());
    }
    private String decrypt(String value) {
        return value == null ? null : InternalSecretCipher.decryptIfNecessary(value, cryptoKey());
    }
    private String cryptoKey() {
        String explicit = environment.getProperty("chatchat.models.crypto-key", "").trim();
        if (!explicit.isBlank()) return explicit;
        String path = environment.getProperty("chatchat.internal-credential.crypto-key-file", "").trim();
        if (!path.isBlank()) {
            try { return Files.readString(Path.of(path)).trim(); }
            catch (Exception ex) { throw new IllegalStateException("Model encryption key file is unavailable: " + path, ex); }
        }
        throw new IllegalStateException("Configure chatchat.models.crypto-key before storing model API keys");
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private void addColumnIfMissing(String column, String type) {
        boolean exists = jdbc.query("SELECT * FROM platform_model_config WHERE 1=0", rs -> {
            var metadata = rs.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                if (column.equalsIgnoreCase(metadata.getColumnName(index))) return true;
            }
            return false;
        });
        if (!exists) jdbc.execute("ALTER TABLE platform_model_config ADD COLUMN " + column + " " + type);
    }
    private static String normalizeType(String type) {
        String value = required(type, "type").toLowerCase(Locale.ROOT);
        if (!value.equals("chat") && !value.equals("embedding"))
            throw new IllegalArgumentException("type must be chat or embedding");
        return value;
    }
    private static void validateUrl(String url) {
        URI uri = URI.create(url);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null) throw new IllegalArgumentException("baseUrl must be an absolute HTTP URL");
    }
}
