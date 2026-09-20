package com.chatchat.api.datascience.skill;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Downloads Markdown/ZIP skill packages from public or explicitly allowed private HTTP endpoints. */
@Component
public class DomainSkillRemoteImporter {
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final int MAX_QUERY_ENTRIES = 50;
    private static final int MAX_HEADER_ENTRIES = 30;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
        "host", "content-length", "connection", "transfer-encoding", "upgrade", "expect");
    private final HttpClient client;

    public DomainSkillRemoteImporter() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    DomainSkillRemoteImporter(HttpClient client) {
        this.client = client;
    }

    public RemoteFile download(String sourceUrl) {
        return download(sourceUrl, DownloadRequest.defaults());
    }

    public RemoteFile download(String sourceUrl, DownloadRequest rawRequest) {
        DownloadRequest request = normalize(rawRequest);
        URI uri = appendQuery(parse(sourceUrl), request.queryParams());
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validateHttpUri(uri, request.allowPrivateNetwork());
            HttpResponse<InputStream> response = send(uri, request);
            if (REDIRECTS.contains(response.statusCode())) {
                close(response.body());
                if (redirect == MAX_REDIRECTS) throw new IllegalArgumentException("Skill URL has too many redirects");
                String location = response.headers().firstValue("location")
                    .orElseThrow(() -> new IllegalArgumentException("Skill URL redirect is missing Location"));
                uri = uri.resolve(location);
                if (response.statusCode() == 303) request = request.asGet();
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                close(response.body());
                throw new IllegalArgumentException("Skill URL returned HTTP " + response.statusCode());
            }
            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (contentLength > DomainSkillService.MAX_UPLOAD_BYTES) {
                close(response.body());
                throw new IllegalArgumentException("Remote skill file must not exceed 5MB");
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            String fileName = fileName(uri, contentType);
            byte[] bytes = readBounded(response.body());
            return new RemoteFile(uri.toString(), fileName, bytes);
        }
        throw new IllegalArgumentException("Unable to download skill URL");
    }

    private DownloadRequest normalize(DownloadRequest raw) {
        DownloadRequest source = raw == null ? DownloadRequest.defaults() : raw;
        String method = source.method() == null || source.method().isBlank()
            ? "GET" : source.method().trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Unsupported skill HTTP method: " + method);

        Map<String, String> query = copyEntries(source.queryParams(), MAX_QUERY_ENTRIES, "query parameter", false);
        Map<String, String> headers = copyEntries(source.headers(), MAX_HEADER_ENTRIES, "request header", true);
        String body = source.body() == null ? "" : source.body();
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Skill HTTP request body must not exceed 256KB");
        }
        if ("GET".equals(method) && !body.isBlank()) {
            throw new IllegalArgumentException("GET skill requests cannot contain a request body");
        }
        return new DownloadRequest(method, query, headers, body, source.allowPrivateNetwork());
    }

    private Map<String, String> copyEntries(Map<String, String> values, int maximum, String label,
                                             boolean validateHeader) {
        if (values == null || values.isEmpty()) return Map.of();
        if (values.size() > maximum) throw new IllegalArgumentException("Too many skill HTTP " + label + "s");
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((rawName, rawValue) -> {
            String name = rawName == null ? "" : rawName.trim();
            String value = rawValue == null ? "" : rawValue;
            if (name.isBlank() || name.length() > 200 || value.length() > 8192
                || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Skill HTTP " + label + " is invalid");
            }
            if (validateHeader && FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Skill HTTP header is managed by the server: " + name);
            }
            result.put(name, value);
        });
        return Map.copyOf(result);
    }

    private URI appendQuery(URI uri, Map<String, String> queryParams) {
        if (queryParams.isEmpty()) return uri;
        StringBuilder query = new StringBuilder();
        queryParams.forEach((name, value) -> {
            if (!query.isEmpty()) query.append('&');
            query.append(encode(name)).append('=').append(encode(value));
        });
        try {
            String source = uri.toASCIIString();
            int fragmentIndex = source.indexOf('#');
            String fragment = fragmentIndex < 0 ? "" : source.substring(fragmentIndex);
            String base = fragmentIndex < 0 ? source : source.substring(0, fragmentIndex);
            URI value = URI.create(base + (uri.getRawQuery() == null ? "?" : "&") + query + fragment);
            if (value.toString().length() > 8192) throw new IllegalArgumentException("Skill URL and query must not exceed 8192 characters");
            return value;
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException argument) throw argument;
            throw new IllegalArgumentException("Skill URL query parameters are invalid", ex);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpResponse<InputStream> send(URI uri, DownloadRequest options) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20));
            if (!containsHeader(options.headers(), "Accept")) {
                builder.header("Accept", "text/markdown, application/zip, application/octet-stream;q=0.8");
            }
            if (!containsHeader(options.headers(), "User-Agent")) {
                builder.header("User-Agent", "ChatChat-SkillImporter/1.0");
            }
            options.headers().forEach(builder::header);
            HttpRequest.BodyPublisher publisher = options.body().isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(options.body(), StandardCharsets.UTF_8);
            HttpRequest request = builder.method(options.method(), publisher).build();
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Skill URL download was interrupted", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unable to download skill URL", ex);
        }
    }

    private boolean containsHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }

    private URI parse(String value) {
        try {
            String normalized = value == null ? "" : value.trim();
            if (normalized.length() > 2048) throw new IllegalArgumentException("Skill URL must not exceed 2048 characters");
            return URI.create(normalized);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Skill URL is invalid", ex);
        }
    }

    private void validateHttpUri(URI uri, boolean allowPrivateNetwork) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null
            || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Skill URL must be an HTTP or HTTPS address without embedded credentials");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isAlwaysBlocked(address)) {
                    throw new IllegalArgumentException("Skill URL must not resolve to a local, link-local, or unsafe address");
                }
                if (!allowPrivateNetwork && isPrivate(address)) {
                    throw new IllegalArgumentException("Skill URL resolves to a private address; enable private network access explicitly");
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Skill URL host cannot be resolved", ex);
        }
    }

    private boolean isAlwaysBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff, second = bytes[1] & 0xff;
            return first == 0 || first == 127 || (first == 169 && second == 254);
        }
        return false;
    }

    private boolean isPrivate(InetAddress address) {
        if (address.isSiteLocalAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff, second = bytes[1] & 0xff;
            return first == 100 && second >= 64 && second <= 127;
        }
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private String fileName(URI uri, String contentType) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1).trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".zip")) return name;
        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.contains("zip")) return name.isBlank() ? "skill.zip" : name + ".zip";
        if (type.contains("markdown") || type.startsWith("text/")) return name.isBlank() ? "SKILL.md" : name + ".md";
        throw new IllegalArgumentException("Skill URL must point to a Markdown or ZIP file");
    }

    private byte[] readBounded(InputStream input) {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > DomainSkillService.MAX_UPLOAD_BYTES) {
                    throw new IllegalArgumentException("Remote skill file must not exceed 5MB");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read remote skill file", ex);
        }
    }

    private void close(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (IOException ignored) { }
    }

    public record DownloadRequest(String method, Map<String, String> queryParams, Map<String, String> headers,
                                  String body, boolean allowPrivateNetwork) {
        public static DownloadRequest defaults() {
            return new DownloadRequest("GET", Map.of(), Map.of(), "", false);
        }

        DownloadRequest asGet() {
            return new DownloadRequest("GET", Map.of(), headers, "", allowPrivateNetwork);
        }
    }

    public record RemoteFile(String sourceUrl, String fileName, byte[] bytes) { }
}
