package com.chatchat.chat.skills.federation;

import com.chatchat.common.skills.federation.SkillSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** SEP-2640 adapter isolated from the evolving MCP Java SDK Skills API. */
@Component
@RequiredArgsConstructor
public class McpSkillSourceRepository implements SkillSourceRepository {
    static final String PROTOCOL_VERSION = "2026-07-28";
    static final String EXTENSION_ID = "io.modelcontextprotocol/skills";
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    @Override public String sourceType() { return "MCP"; }

    public void verifyCapabilities(SourceConnection source) {
        JsonNode result = invoke(source, "server/discover", Map.of());
        JsonNode capabilities = result.path("capabilities");
        if (!capabilities.path("resources").isObject()) {
            throw new IllegalArgumentException("MCP Skill source does not declare the resources capability");
        }
        if (!capabilities.path("extensions").path(EXTENSION_ID).isObject()) {
            throw new IllegalArgumentException("MCP server does not declare the Skills extension");
        }
    }

    @Override
    public SkillPage discover(SourceConnection source, String cursor, int pageSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (cursor != null && !cursor.isBlank()) params.put("cursor", cursor);
        JsonNode result = invoke(source, "skills/list", params);
        validateCacheableResult(result, "skills/list");
        List<SkillManifest> skills = new ArrayList<>();
        JsonNode entries = result.path("skills");
        if (entries.isArray()) entries.forEach(node -> skills.add(manifest(node, result)));
        return new SkillPage(skills, text(result.get("nextCursor")));
    }

    @Override
    public SkillManifest describe(SourceConnection source, String skillUri) {
        JsonNode result = invoke(source, "skills/get", Map.of("uri", requiredUri(skillUri)));
        validateCacheableResult(result, "skills/get");
        JsonNode entry = result.has("skill") ? result.path("skill") : result;
        return manifest(entry, result);
    }

    @Override
    public SkillResource read(SourceConnection source, String resourceUri) {
        JsonNode result = invoke(source, "resources/read", Map.of("uri", requiredUri(resourceUri)));
        JsonNode contents = result.path("contents");
        if (!contents.isArray() || contents.isEmpty()) {
            throw new IllegalArgumentException("MCP resource response is empty: " + resourceUri);
        }
        JsonNode content = contents.get(0);
        byte[] bytes;
        if (content.hasNonNull("text")) bytes = content.path("text").asText().getBytes(StandardCharsets.UTF_8);
        else if (content.hasNonNull("blob")) bytes = Base64.getDecoder().decode(content.path("blob").asText());
        else throw new IllegalArgumentException("MCP resource has neither text nor blob content: " + resourceUri);
        return new SkillResource(resourceUri, text(content.get("mimeType")), bytes);
    }

    private SkillManifest manifest(JsonNode node, JsonNode envelope) {
        String uri = requiredUri(text(node.get("uri")));
        Map<String, Object> frontmatter = objectMapper.convertValue(node.path("frontmatter"), Map.class);
        JsonNode resourcesNode = node.get("resources");
        boolean dynamic = resourcesNode != null && resourcesNode.isTextual()
            && "dynamic".equalsIgnoreCase(resourcesNode.asText());
        List<ResourceDescriptor> resources = new ArrayList<>();
        if (resourcesNode != null && resourcesNode.isArray()) {
            resourcesNode.forEach(resource -> resources.add(new ResourceDescriptor(
                requiredUri(text(resource.get("uri"))), text(resource.get("digest")), resource.path("size").asLong(-1))));
        }
        if (!(frontmatter.get("name") instanceof String name) || name.isBlank()
            || !(frontmatter.get("description") instanceof String description) || description.isBlank()) {
            throw new IllegalArgumentException("MCP Skill frontmatter must contain name and description: " + uri);
        }
        validateManifest(uri, frontmatter, resources, dynamic, resourcesNode);
        Long ttl = envelope.hasNonNull("ttlMs") ? envelope.path("ttlMs").asLong() : null;
        return new SkillManifest(uri, frontmatter, resources, dynamic, ttl, text(envelope.get("cacheScope")));
    }

    private void validateManifest(String skillUri, Map<String, Object> frontmatter,
                                  List<ResourceDescriptor> resources, boolean dynamic, JsonNode resourcesNode) {
        if (resourcesNode == null || (!resourcesNode.isArray() && !dynamic)) {
            throw new IllegalArgumentException("MCP Skill resources must be a complete array or dynamic: " + skillUri);
        }
        String suffix = "/SKILL.md";
        if (!skillUri.endsWith(suffix)) throw new IllegalArgumentException("MCP Skill URI must address SKILL.md: " + skillUri);
        String root = skillUri.substring(0, skillUri.length() - suffix.length());
        int scheme = root.indexOf("://");
        String path = scheme < 0 ? root : root.substring(scheme + 3);
        String expectedName = path.substring(path.lastIndexOf('/') + 1);
        if (!expectedName.equals(String.valueOf(frontmatter.get("name")).trim())) {
            throw new IllegalArgumentException("MCP Skill URI path does not match frontmatter name: " + skillUri);
        }
        if (dynamic) return;
        Set<String> seen = new HashSet<>();
        for (ResourceDescriptor resource : resources) {
            if (!resource.uri().equals(skillUri) && !resource.uri().startsWith(root + "/")) {
                throw new IllegalArgumentException("MCP Skill resource is outside its skill directory: " + resource.uri());
            }
            if (!seen.add(resource.uri())) throw new IllegalArgumentException("MCP Skill resource is duplicated: " + resource.uri());
            if (resource.size() < 0 || resource.digest() == null
                || !resource.digest().matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("MCP Skill resource digest or size is invalid: " + resource.uri());
            }
        }
        if (!seen.contains(skillUri)) throw new IllegalArgumentException("MCP Skill manifest does not include SKILL.md: " + skillUri);
    }

    private void validateCacheableResult(JsonNode result, String method) {
        if (!"complete".equals(result.path("resultType").asText())
            || !result.hasNonNull("ttlMs") || result.path("ttlMs").asLong(-1) < 0
            || text(result.get("cacheScope")).isBlank()) {
            throw new IllegalArgumentException("MCP " + method + " did not return a complete cacheable result");
        }
    }

    private JsonNode invoke(SourceConnection source, String method, Map<String, Object> methodParams) {
        try {
            Map<String, Object> meta = Map.of(
                "io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION,
                "io.modelcontextprotocol/clientInfo", Map.of("name", "chatchat-runtime-os", "version", "1.0"),
                "io.modelcontextprotocol/clientCapabilities", Map.of("extensions", Map.of(EXTENSION_ID, Map.of()))
            );
            Map<String, Object> params = new LinkedHashMap<>(methodParams == null ? Map.of() : methodParams);
            params.put("_meta", meta);
            byte[] payload = objectMapper.writeValueAsBytes(Map.of(
                "jsonrpc", "2.0", "id", UUID.randomUUID().toString(), "method", method, "params", params));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(source.endpoint()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            if (source.authorization() != null && !source.authorization().isBlank()) {
                request.header("Authorization", source.authorization().trim());
            }
            HttpResponse<byte[]> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.body().length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("MCP response exceeds 4MB");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("MCP request " + method + " returned HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(jsonBody(response.body()));
            if (root.has("error")) throw new IllegalArgumentException("MCP " + method + " failed: "
                + root.path("error").path("message").asText("unknown error"));
            JsonNode result = root.get("result");
            if (result == null || !result.isObject()) throw new IllegalArgumentException("MCP " + method + " returned no result");
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("MCP Skills request was interrupted", ex);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to call MCP Skills method " + method, ex);
        }
    }

    private byte[] jsonBody(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (!text.startsWith("data:")) return body;
        String data = text.lines().filter(line -> line.startsWith("data:"))
            .map(line -> line.substring(5).trim()).reduce((first, second) -> second).orElse("");
        return data.getBytes(StandardCharsets.UTF_8);
    }

    private String requiredUri(String value) {
        String uri = value == null ? "" : value.trim();
        if (uri.isBlank() || uri.length() > 4096) throw new IllegalArgumentException("MCP Skill resource URI is invalid");
        return uri;
    }

    private String text(JsonNode value) { return value == null || value.isNull() ? "" : value.asText("").trim(); }
}
