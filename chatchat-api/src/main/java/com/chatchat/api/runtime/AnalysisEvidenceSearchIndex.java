package com.chatchat.api.runtime;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContexts;
import org.opensearch.client.Request;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;

/** Rebuildable metadata-only OpenSearch index; PostgreSQL remains the authority for reads. */
@Component
public class AnalysisEvidenceSearchIndex {
    private final SearchProperties properties;
    private final ObjectMapper mapper;
    private final String indexName;

    public AnalysisEvidenceSearchIndex(SearchProperties properties, ObjectMapper mapper,
                                       @Value("${chatchat.analysis.evidence.index-name:chatchat-analysis-evidence-v1}")
                                       String indexName) {
        this.properties = properties;
        this.mapper = mapper;
        if (indexName == null || !indexName.matches("[a-z][a-z0-9_-]{2,100}"))
            throw new IllegalArgumentException("Invalid analysis evidence index name");
        this.indexName = indexName;
    }

    public boolean enabled() {
        var config = properties.getOpenSearch();
        return properties.isOpenSearchEngine() && config != null && config.isEnabled()
            && config.getUrl() != null && !config.getUrl().isBlank();
    }

    public void index(AnalysisEvidenceArchiveEntity entity) {
        if (!enabled()) return;
        try (RestClient client = client()) {
            ensureIndex(client);
            Request request = new Request("PUT", "/" + indexName + "/_doc/" + entity.archiveId);
            request.setJsonEntity(mapper.writeValueAsString(Map.of(
                "archiveId", entity.archiveId, "tenantId", entity.tenantId, "userId", entity.userId,
                "runId", entity.runId, "sha256", entity.sha256,
                "byteLength", entity.byteLength, "createdAtEpochMs", entity.createdAtEpochMs)));
            client.performRequest(request);
        } catch (Exception failure) {
            throw new IllegalStateException("Analysis evidence metadata indexing failed", failure);
        }
    }

    public void remove(String archiveId) {
        if (!enabled()) return;
        try (RestClient client = client()) {
            client.performRequest(new Request("DELETE", "/" + indexName + "/_doc/" + archiveId));
        } catch (org.opensearch.client.ResponseException response) {
            if (response.getResponse().getStatusLine().getStatusCode() != 404)
                throw new IllegalStateException("Analysis evidence index deletion failed", response);
        } catch (Exception failure) {
            throw new IllegalStateException("Analysis evidence index deletion failed", failure);
        }
    }

    private void ensureIndex(RestClient client) throws Exception {
        try {
            client.performRequest(new Request("HEAD", "/" + indexName));
            return;
        } catch (org.opensearch.client.ResponseException missing) {
            if (missing.getResponse().getStatusLine().getStatusCode() != 404) throw missing;
        }
        Map<String, Object> keyword = Map.of("type", "keyword");
        Map<String, Object> fields = Map.of(
            "archiveId", keyword, "tenantId", keyword, "userId", keyword,
            "runId", keyword, "sha256", keyword,
            "byteLength", Map.of("type", "long"),
            "createdAtEpochMs", Map.of("type", "long"));
        Request create = new Request("PUT", "/" + indexName);
        create.setJsonEntity(mapper.writeValueAsString(Map.of("mappings", Map.of("properties", fields))));
        try { client.performRequest(create); }
        catch (org.opensearch.client.ResponseException race) {
            if (race.getResponse().getStatusLine().getStatusCode() != 400) throw race;
            client.performRequest(new Request("HEAD", "/" + indexName));
        }
    }

    private RestClient client() throws GeneralSecurityException {
        var config = properties.getOpenSearch();
        RestClientBuilder builder = RestClient.builder(HttpHost.create(config.getUrl()));
        builder.setRequestConfigCallback(request -> request
            .setConnectTimeout(Math.max(1000, config.getConnectTimeoutMs()))
            .setSocketTimeout(Math.max(1000, config.getRequestTimeoutMs())));
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            String password = config.getPassword() == null ? "" : config.getPassword();
            String credential = Base64.getEncoder().encodeToString((config.getUsername() + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
            builder.setDefaultHeaders(new BasicHeader[]{new BasicHeader("Authorization", "Basic " + credential)});
        }
        if (config.isInsecureSsl()) {
            SSLContext ssl = SSLContexts.custom().loadTrustMaterial(null, (chain, authType) -> true).build();
            builder.setHttpClientConfigCallback(http -> http.setSSLContext(ssl)
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE));
        }
        return builder.build();
    }
}
