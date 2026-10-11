package com.chatchat.mcpserver.datacapability.connection;

import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import com.chatchat.mcpserver.search.engine.LuceneSearchProperties;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.util.EntityUtils;
import org.neo4j.driver.*;
import org.neo4j.driver.types.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Official, bundled search clients and Bolt driver; independent from dynamic JDBC loading. */
@Component
public class NativeDatabaseClient implements AutoCloseable {
    private final ObjectMapper json;
    private final NativeClientPool<AutoCloseable> clients = new NativeClientPool<>(64);
    private LuceneSearchProperties searchProperties;
    public NativeDatabaseClient(ObjectMapper json) { this.json = json; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureOpenSearch(LuceneSearchProperties properties) { this.searchProperties = properties; }

    public JsonNode get(HttpEndpointConfig asset, String path, int timeoutSeconds) throws Exception {
        return search(asset, "GET", path, null, timeoutSeconds, Map.of());
    }
    public JsonNode post(HttpEndpointConfig asset, String path, Object body, int timeoutSeconds,
                         Map<String,String> headers) throws Exception {
        return search(asset, "POST", path, body, timeoutSeconds, headers);
    }
    private JsonNode search(HttpEndpointConfig asset, String method, String path, Object body,
                            int timeoutSeconds, Map<String,String> headers) throws Exception {
        String kind = "elasticsearch".equalsIgnoreCase(asset.getCategory()) ? "elasticsearch" : "opensearch";
        if (!Set.of("elasticsearch", "opensearch", "search_engine").contains(String.valueOf(asset.getCategory()).toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Asset is not a search engine connection");
        URI endpoint = URI.create(asset.getUrlTemplate());
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
            || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("Search client requires an HTTP(S) base address");
        int timeout = timeoutMillis(asset, timeoutSeconds);
        var config = RequestConfig.custom().setConnectTimeout(Math.min(10000, timeout))
            .setConnectionRequestTimeout(timeout).setSocketTimeout(timeout).setRedirectsEnabled(false).build();
        boolean insecure = usesConfiguredOpenSearchTlsPolicy(asset, kind);
        try (var lease = acquire(asset, kind + ":tls=" + insecure, () -> searchClient(asset, kind, insecure))) {
            CompletableFuture<String> response = new CompletableFuture<>();
            Runnable cancel;
            // Pass query parameters through the official request API, not as part of its endpoint.
            URI target = URI.create(path);
            if (!path.startsWith("/") || target.isAbsolute() || target.getFragment() != null)
                throw new IllegalArgumentException("Invalid search request path");
            if (lease.client() instanceof org.elasticsearch.client.RestClient client) {
                var request = new org.elasticsearch.client.Request(method, target.getPath());
                addParameters(target, request::addParameter);
                if (body != null) request.setJsonEntity(json.writeValueAsString(body));
                var options = org.elasticsearch.client.RequestOptions.DEFAULT.toBuilder().setRequestConfig(config);
                headers.forEach(options::addHeader); request.setOptions(options);
                var pending = client.performRequestAsync(request, new org.elasticsearch.client.ResponseListener() {
                    public void onSuccess(org.elasticsearch.client.Response value) { complete(response, value.getEntity()); }
                    public void onFailure(Exception error) { response.completeExceptionally(error); }
                });
                cancel = pending::cancel;
            } else {
                var client = (org.opensearch.client.RestClient) lease.client();
                var request = new org.opensearch.client.Request(method, target.getPath());
                addParameters(target, request::addParameter);
                if (body != null) request.setJsonEntity(json.writeValueAsString(body));
                var options = org.opensearch.client.RequestOptions.DEFAULT.toBuilder().setRequestConfig(config);
                headers.forEach(options::addHeader); request.setOptions(options);
                var pending = client.performRequestAsync(request, new org.opensearch.client.ResponseListener() {
                    public void onSuccess(org.opensearch.client.Response value) { complete(response, value.getEntity()); }
                    public void onFailure(Exception error) { response.completeExceptionally(error); }
                });
                cancel = pending::cancel;
            }
            try { return json.readTree(response.get(timeout, TimeUnit.MILLISECONDS)); }
            catch (TimeoutException ex) { cancel.run(); throw new TimeoutException("Search request deadline exceeded"); }
            catch (InterruptedException ex) { cancel.run(); Thread.currentThread().interrupt(); throw ex; }
            catch (ExecutionException ex) {
                if (ex.getCause() instanceof Exception cause) throw cause;
                throw ex;
            }
        }
    }
    private void complete(CompletableFuture<String> result, org.apache.http.HttpEntity entity) {
        try { result.complete(EntityUtils.toString(entity, StandardCharsets.UTF_8)); }
        catch (Exception ex) { result.completeExceptionally(ex); }
    }
    private void addParameters(URI target, java.util.function.BiConsumer<String,String> add) {
        if (target.getRawQuery() == null) return;
        for (String item : target.getRawQuery().split("&")) {
            String[] pair = item.split("=", 2);
            add.accept(java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair.length == 2 ? java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
    }
    boolean usesConfiguredOpenSearchTlsPolicy(HttpEndpointConfig asset, String kind) {
        if (!"opensearch".equals(kind) || searchProperties == null || searchProperties.getOpenSearch() == null
            || !searchProperties.getOpenSearch().isInsecureSsl()) return false;
        String configured = searchProperties.getOpenSearch().getUrl();
        return configured != null && URI.create(configured.replaceAll("/+$", ""))
            .equals(URI.create(asset.getUrlTemplate().replaceAll("/+$", "")));
    }
    private org.apache.http.impl.nio.client.HttpAsyncClientBuilder configureTransport(
        org.apache.http.impl.nio.client.HttpAsyncClientBuilder client, boolean insecure) {
        client.setMaxConnTotal(16).setMaxConnPerRoute(16);
        if (insecure) {
            try {
                client.setSSLContext(org.apache.http.ssl.SSLContexts.custom().loadTrustMaterial(null, (chain, authType) -> true).build());
                client.setSSLHostnameVerifier(org.apache.http.conn.ssl.NoopHostnameVerifier.INSTANCE);
            } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("Invalid configured OpenSearch TLS policy", ex); }
        }
        return client;
    }
    private AutoCloseable searchClient(HttpEndpointConfig asset, String kind, boolean insecure) {
        URI uri = URI.create(asset.getUrlTemplate());
        HttpHost host = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
        var headers = headers(asset);
        org.apache.http.Header[] defaults = headers.entrySet().stream()
            .map(e -> new BasicHeader(e.getKey(), e.getValue())).toArray(org.apache.http.Header[]::new);
        String prefix = URI.create(asset.getUrlTemplate()).getPath().replaceAll("/+$", "");
        if (kind.equals("elasticsearch")) {
            var builder = org.elasticsearch.client.RestClient.builder(host).setDefaultHeaders(defaults)
                .setHttpClientConfigCallback(client -> configureTransport(client, insecure));
            if (!prefix.isBlank()) builder.setPathPrefix(prefix);
            return builder.build();
        }
        var builder = org.opensearch.client.RestClient.builder(host).setDefaultHeaders(defaults)
            .setHttpClientConfigCallback(client -> configureTransport(client, insecure));
        if (!prefix.isBlank()) builder.setPathPrefix(prefix);
        return builder.build();
    }

    /** Adapt typed Bolt records to the existing query/metadata result contract, without an HTTP transaction. */
    public JsonNode cypher(HttpEndpointConfig asset, String database, List<Map<String,Object>> statements,
                           int timeoutSeconds) throws Exception {
        int timeout = timeoutMillis(asset, timeoutSeconds);
        try (var lease = acquire(asset, "neo4j:" + timeout, () -> graphClient(asset, timeout));
             var session = ((Driver) lease.client()).session(SessionConfig.builder()
                 .withDatabase(database).withDefaultAccessMode(AccessMode.READ).build())) {
            return session.executeRead(tx -> {
                List<Map<String,Object>> results = new ArrayList<>();
                for (var statement : statements) {
                    @SuppressWarnings("unchecked")
                    Map<String,Object> parameters = (Map<String,Object>) statement.getOrDefault("parameters", Map.of());
                    var result = tx.run(String.valueOf(statement.get("statement")), parameters);
                    var columns = result.keys();
                    List<Map<String,Object>> rows = new ArrayList<>();
                    while (result.hasNext()) {
                        if (rows.size() >= 50001) throw new IllegalStateException("Neo4j result exceeds row limit");
                        var record = result.next();
                        Map<String,Object> nodes = new LinkedHashMap<>(), relationships = new LinkedHashMap<>();
                        List<Object> values = new ArrayList<>();
                        for (String key : columns) values.add(convert(record.get(key).asObject(), nodes, relationships));
                        rows.add(Map.of("row", values, "graph", Map.of("nodes", List.copyOf(nodes.values()),
                            "relationships", List.copyOf(relationships.values()))));
                    }
                    result.consume();
                    results.add(Map.of("columns", columns, "data", rows));
                }
                return json.valueToTree(Map.of("results", results, "errors", List.of()));
            }, TransactionConfig.builder().withTimeout(Duration.ofMillis(timeout)).build());
        }
    }
    private Driver graphClient(HttpEndpointConfig asset, int timeout) {
        URI uri = URI.create(asset.getUrlTemplate());
        if (!Set.of("bolt", "bolt+s", "bolt+ssc", "neo4j", "neo4j+s", "neo4j+ssc").contains(uri.getScheme())
            || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")))
            throw new IllegalArgumentException("Neo4j requires a Bolt/neo4j URI, e.g. bolt://host:7687; HTTP endpoints are no longer supported");
        AuthToken auth = AuthTokens.none();
        String header = headers(asset).entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Authorization"))
            .map(Map.Entry::getValue).findFirst().orElse(null);
        if (header != null) {
            if (!header.regionMatches(true, 0, "Basic ", 0, 6))
                throw new IllegalArgumentException("Neo4j Bolt connections require username/password credentials");
            String credentials = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            int separator = credentials.indexOf(':');
            if (separator < 0) throw new IllegalArgumentException("Invalid Neo4j credentials");
            auth = AuthTokens.basic(credentials.substring(0, separator), credentials.substring(separator + 1));
        }
        return createDriver(uri, auth, Config.builder().withMaxConnectionPoolSize(16)
            .withConnectionTimeout(Math.min(10000, timeout), TimeUnit.MILLISECONDS)
            .withConnectionAcquisitionTimeout(timeout, TimeUnit.MILLISECONDS)
            .withMaxTransactionRetryTime(0, TimeUnit.MILLISECONDS)
            .withMaxConnectionLifetime(30, TimeUnit.MINUTES).build());
    }
    protected Driver createDriver(URI uri, AuthToken auth, Config config) { return GraphDatabase.driver(uri, auth, config); }
    private Object convert(Object value, Map<String,Object> nodes, Map<String,Object> relationships) {
        if (value instanceof Node node) {
            String id = node.elementId();
            List<String> labels = new ArrayList<>(); node.labels().forEach(labels::add);
            Object properties = convert(node.asMap(), nodes, relationships);
            nodes.put(id, Map.of("id", id, "elementId", node.elementId(), "labels", labels, "properties", properties));
            return properties;
        }
        if (value instanceof Relationship relationship) {
            String id = relationship.elementId();
            Object properties = convert(relationship.asMap(), nodes, relationships);
            relationships.put(id, Map.of("id", id, "elementId", relationship.elementId(), "type", relationship.type(),
                "startNode", relationship.startNodeElementId(), "endNode", relationship.endNodeElementId(),
                "properties", properties));
            return properties;
        }
        if (value instanceof Path path) {
            List<Object> parts = new ArrayList<>();
            for (Path.Segment segment : path) {
                parts.add(convert(segment.start(), nodes, relationships));
                parts.add(convert(segment.relationship(), nodes, relationships));
                convert(segment.end(), nodes, relationships);
            }
            parts.add(convert(path.end(), nodes, relationships)); return parts;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> convert(item, nodes, relationships)).toList();
        if (value instanceof Map<?,?> map) {
            Map<String,Object> converted = new LinkedHashMap<>();
            map.forEach((key, item) -> converted.put(String.valueOf(key), convert(item, nodes, relationships))); return converted;
        }
        if (value instanceof java.time.temporal.TemporalAccessor || value instanceof IsoDuration) return value.toString();
        if (value instanceof Point point) {
            Map<String,Object> coordinates = new LinkedHashMap<>(Map.of("srid", point.srid(), "x", point.x(), "y", point.y()));
            if (Double.isFinite(point.z())) coordinates.put("z", point.z());
            return coordinates;
        }
        return value;
    }
    private Map<String,String> headers(HttpEndpointConfig asset) {
        try {
            if (asset.getHeadersJson() == null || asset.getHeadersJson().isBlank()) return Map.of();
            JsonNode value = json.readTree(asset.getHeadersJson());
            if (!value.isObject()) throw new IllegalArgumentException("Asset headers must be a JSON object");
            Map<String,String> result = new LinkedHashMap<>();
            value.fields().forEachRemaining(e -> {
                if (!e.getValue().isTextual()) throw new IllegalArgumentException("Asset header values must be strings");
                result.put(e.getKey(), e.getValue().textValue());
            });
            return result;
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("Invalid asset headers", ex); }
    }
    private NativeClientPool.Lease<AutoCloseable> acquire(HttpEndpointConfig asset, String kind, java.util.function.Supplier<AutoCloseable> factory) {
        try {
            String config = kind + "\n" + asset.getUrlTemplate() + "\n" + asset.getHeadersJson() + "\n" + asset.getTimeoutMs();
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(config.getBytes(StandardCharsets.UTF_8)));
            String identity = asset.getId() == null ? kind + ":" + asset.getUrlTemplate() : asset.getId();
            return clients.acquire(identity, fingerprint, factory);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private int timeoutMillis(HttpEndpointConfig asset, int seconds) {
        return (int) Math.max(1, Math.min(Math.max(1, seconds) * 1000L, Math.max(1, asset.getTimeoutMs())));
    }
    @PreDestroy @Override public void close() { clients.close(); }
}
