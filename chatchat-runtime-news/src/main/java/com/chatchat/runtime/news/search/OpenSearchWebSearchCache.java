package com.chatchat.runtime.news.search;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "chatchat.runtime.news.open-search", name = "enabled", havingValue = "true")
public class OpenSearchWebSearchCache implements WebSearchCache {
    private static final DateTimeFormatter INDEX_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final NewsRuntimeProperties.Cache properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;
    private final Clock clock;
    private final Set<String> readyIndices = ConcurrentHashMap.newKeySet();

    @Autowired
    public OpenSearchWebSearchCache(NewsRuntimeProperties runtimeProperties, ObjectMapper objectMapper) {
        this(runtimeProperties, objectMapper,
            Clock.system(ZoneId.of(runtimeProperties.getWebSearch().getCache().getZoneId())));
    }

    OpenSearchWebSearchCache(NewsRuntimeProperties runtimeProperties, ObjectMapper objectMapper, Clock clock) {
        this.properties = runtimeProperties.getWebSearch().getCache();
        this.objectMapper = objectMapper;
        this.clock = clock;
        NewsRuntimeProperties.OpenSearch openSearch = runtimeProperties.getOpenSearch();
        java.net.URI endpoint = java.net.URI.create(openSearch.getEndpoint());
        var builder = RestClient.builder(new HttpHost(endpoint.getHost(),
            endpoint.getPort() < 0 ? ("https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80) : endpoint.getPort(),
            endpoint.getScheme()));
        BasicCredentialsProvider credentials = new BasicCredentialsProvider();
        boolean hasCredentials = openSearch.getUsername() != null && !openSearch.getUsername().isBlank();
        if (hasCredentials) {
            credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
                openSearch.getUsername(), openSearch.getPassword() == null ? "" : openSearch.getPassword()));
        }
        builder.setHttpClientConfigCallback(http -> {
            if (hasCredentials) http.setDefaultCredentialsProvider(credentials);
            if (openSearch.isInsecureSsl()) {
                try {
                    http.setSSLContext(SSLContexts.custom().loadTrustMaterial(null, (chain, authType) -> true).build());
                    http.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                } catch (GeneralSecurityException ex) {
                    throw new IllegalStateException("Failed to configure web-search cache SSL", ex);
                }
            }
            return http;
        });
        this.client = builder.build();
    }

    @Override
    public Optional<CachedSearch> findHighlyRelated(String query) throws Exception {
        if (!enabled() || query == null || query.isBlank()) return Optional.empty();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", Math.max(1, properties.getMaxCandidates()));
        ObjectNode bool = body.putObject("query").putObject("bool");
        bool.putArray("must").addObject().putObject("match").putObject("query")
            .put("query", query).put("operator", "or");
        bool.putArray("filter").addObject().putObject("range").putObject("fetchedAt")
            .put("gte", "now-" + Math.max(1, properties.getRetentionDays()) + "d");
        Request request = new Request("POST", "/" + indexPattern() + "/_search");
        request.addParameter("ignore_unavailable", "true");
        request.addParameter("allow_no_indices", "true");
        request.setJsonEntity(objectMapper.writeValueAsString(body));
        JsonNode response;
        try {
            response = read(client.performRequest(request));
        } catch (ResponseException ex) {
            if (status(ex) == 404) return Optional.empty();
            throw ex;
        }
        CachedSearch best = null;
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            String cachedQuery = source.path("query").asText();
            double similarity = similarity(query, cachedQuery);
            if (similarity + 1.0e-9D < boundedSimilarity()) continue;
            TencentWebSearchClient.SearchResponse cached =
                objectMapper.treeToValue(source.path("response"), TencentWebSearchClient.SearchResponse.class);
            if (cached.pages() == null || cached.pages().isEmpty()) continue;
            if (best == null || similarity > best.similarity()) {
                best = new CachedSearch(cachedQuery, similarity, cached);
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public void put(String query, TencentWebSearchClient.SearchResponse response) throws Exception {
        if (!enabled() || query == null || query.isBlank() || response == null
            || response.pages() == null || response.pages().isEmpty()) return;
        String index = writeIndex();
        ensureIndex(index);
        ObjectNode source = objectMapper.createObjectNode();
        source.put("query", query.trim());
        source.put("normalizedQuery", normalize(query));
        source.put("fetchedAt", clock.instant().toString());
        source.set("response", objectMapper.valueToTree(response));
        Request request = new Request("PUT", "/" + index + "/_doc/" + digest(normalize(query)));
        request.setJsonEntity(objectMapper.writeValueAsString(source));
        client.performRequest(request);
    }

    @Override
    public List<String> deleteExpiredIndices() throws Exception {
        if (!enabled()) return List.of();
        Request request = new Request("GET", "/_cat/indices/" + indexPattern());
        request.addParameter("format", "json");
        request.addParameter("h", "index");
        request.addParameter("expand_wildcards", "open,closed");
        JsonNode indices;
        try {
            indices = read(client.performRequest(request));
        } catch (ResponseException ex) {
            if (status(ex) == 404) return List.of();
            throw ex;
        }
        List<String> deleted = new ArrayList<>();
        for (JsonNode item : indices) {
            String index = item.path("index").asText();
            if (!expired(index)) continue;
            try {
                client.performRequest(new Request("DELETE", "/" + index));
                deleted.add(index);
            } catch (ResponseException ex) {
                if (status(ex) != 404) throw ex;
            }
        }
        return deleted;
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled();
    }

    private void ensureIndex(String index) throws Exception {
        if (readyIndices.contains(index)) return;
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode fields = root.putObject("mappings").putObject("properties");
        fields.putObject("query").put("type", "text");
        fields.putObject("normalizedQuery").put("type", "keyword");
        fields.putObject("fetchedAt").put("type", "date");
        fields.putObject("response").put("type", "object").put("enabled", false);
        Request request = new Request("PUT", "/" + index);
        request.setJsonEntity(objectMapper.writeValueAsString(root));
        try {
            client.performRequest(request);
        } catch (ResponseException ex) {
            if (status(ex) != 400) throw ex;
        }
        readyIndices.add(index);
    }

    private String writeIndex() {
        return properties.getIndexName() + "-" + INDEX_DATE.format(LocalDate.now(clock));
    }

    private String indexPattern() {
        return properties.getIndexName() + "-*";
    }

    private boolean expired(String index) {
        String prefix = properties.getIndexName() + "-";
        if (!index.startsWith(prefix)) return false;
        try {
            LocalDate date = LocalDate.parse(index.substring(prefix.length()), INDEX_DATE);
            LocalDate oldest = LocalDate.now(clock).minusDays(Math.max(1, properties.getRetentionDays()) - 1L);
            return date.isBefore(oldest);
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    static double similarity(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft.equals(normalizedRight)) return 1D;
        Set<String> leftTokens = tokens(normalizedLeft);
        Set<String> rightTokens = tokens(normalizedRight);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0D;
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        double jaccard = intersection.size() / (double) union.size();
        double containment = intersection.size() / (double) Math.min(leftTokens.size(), rightTokens.size());
        return Math.max(jaccard, containment);
    }

    private static Set<String> tokens(String value) {
        Set<String> tokens = new HashSet<>();
        for (String word : value.split("\\s+")) {
            if (!word.isBlank()) tokens.add(word);
        }
        String compact = value.replace(" ", "");
        for (int i = 0; i + 1 < compact.length(); i++) {
            tokens.add(compact.substring(i, i + 2));
        }
        return tokens;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim()
            .replaceAll("\\s+", " ");
    }

    private double boundedSimilarity() {
        return Math.max(0D, Math.min(1D, properties.getMinimumSimilarity()));
    }

    private String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private int status(ResponseException ex) {
        return ex.getResponse() == null ? -1 : ex.getResponse().getStatusLine().getStatusCode();
    }

    private JsonNode read(Response response) throws Exception {
        try (InputStream input = response.getEntity().getContent()) {
            return objectMapper.readTree(input);
        }
    }

    @PreDestroy
    public void close() throws Exception {
        client.close();
    }
}
