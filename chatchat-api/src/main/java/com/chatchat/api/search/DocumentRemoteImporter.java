package com.chatchat.api.search;

import com.chatchat.knowledgebase.search.config.SearchProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
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

/** Downloads one supported library document from a configurable HTTP endpoint. */
@Component
public class DocumentRemoteImporter {
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_REQUEST_BODY_BYTES = 256 * 1024;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> EXTENSIONS = Set.of(
        ".txt", ".md", ".markdown", ".sql", ".csv", ".pdf", ".doc", ".docx", ".xls", ".xlsx");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
        "host", "content-length", "connection", "transfer-encoding", "upgrade", "expect");

    private final HttpClient client;
    private final long maxDownloadBytes;

    public DocumentRemoteImporter(SearchProperties properties) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build(), properties.getMaxUploadBytes());
    }

    DocumentRemoteImporter(HttpClient client, long maxDownloadBytes) {
        this.client = client;
        this.maxDownloadBytes = maxDownloadBytes;
    }

    public RemoteDocument download(String sourceUrl, RequestOptions rawOptions) {
        return download(sourceUrl, rawOptions, null);
    }

    public RemoteDocument download(String sourceUrl, RequestOptions rawOptions, String documentTypeHint) {
        RequestOptions options = normalize(rawOptions);
        URI uri = appendQuery(parse(sourceUrl), options.queryParams());
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validateHttpUri(uri, options.allowPrivateNetwork());
            HttpResponse<InputStream> response = send(uri, options);
            if (REDIRECTS.contains(response.statusCode())) {
                close(response.body());
                if (redirect == MAX_REDIRECTS) throw new IllegalArgumentException("Document URL has too many redirects");
                String location = response.headers().firstValue("location")
                    .orElseThrow(() -> new IllegalArgumentException("Document URL redirect is missing Location"));
                URI redirected = uri.resolve(location);
                options = options.forRedirect(uri, redirected, response.statusCode());
                uri = redirected;
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                close(response.body());
                throw new IllegalArgumentException("Document URL returned HTTP " + response.statusCode());
            }
            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (contentLength > maxDownloadBytes) {
                close(response.body());
                throw new IllegalArgumentException("Remote document exceeds the 55MB upload limit");
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            String disposition = response.headers().firstValue("content-disposition").orElse("");
            String fileName = resolveFileName(uri, contentType, disposition, documentTypeHint);
            return new RemoteDocument(uri.toString(), fileName, contentType, readBounded(response.body()));
        }
        throw new IllegalArgumentException("Unable to download document URL");
    }

    private RequestOptions normalize(RequestOptions raw) {
        RequestOptions source = raw == null ? RequestOptions.defaults() : raw;
        String method = source.method() == null || source.method().isBlank()
            ? "GET" : source.method().trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Unsupported document HTTP method: " + method);
        Map<String, String> query = copyEntries(source.queryParams(), 50, "query parameter", false);
        Map<String, String> headers = copyEntries(source.headers(), 30, "request header", true);
        String body = source.body() == null ? "" : source.body();
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BODY_BYTES) {
            throw new IllegalArgumentException("Document HTTP request body must not exceed 256KB");
        }
        if ("GET".equals(method) && !body.isBlank()) {
            throw new IllegalArgumentException("GET document requests cannot contain a request body");
        }
        return new RequestOptions(method, query, headers, body, source.allowPrivateNetwork());
    }

    private Map<String, String> copyEntries(Map<String, String> values, int maximum, String label,
                                             boolean validateHeader) {
        if (values == null || values.isEmpty()) return Map.of();
        if (values.size() > maximum) throw new IllegalArgumentException("Too many document HTTP " + label + "s");
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((rawName, rawValue) -> {
            String name = rawName == null ? "" : rawName.trim();
            String value = rawValue == null ? "" : rawValue;
            if (name.isBlank() || name.length() > 200 || value.length() > 8192
                || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Document HTTP " + label + " is invalid");
            }
            if (validateHeader && FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Document HTTP header is managed by the server: " + name);
            }
            result.put(name, value);
        });
        return Map.copyOf(result);
    }

    private HttpResponse<InputStream> send(URI uri, RequestOptions options) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60));
            if (!containsHeader(options.headers(), "Accept")) builder.header("Accept", "*/*");
            if (!containsHeader(options.headers(), "User-Agent")) builder.header("User-Agent", "ChatChat-DocumentImporter/1.0");
            options.headers().forEach(builder::header);
            HttpRequest.BodyPublisher publisher = options.body().isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(options.body(), StandardCharsets.UTF_8);
            return client.send(builder.method(options.method(), publisher).build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Document URL download was interrupted", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unable to download document URL", ex);
        }
    }

    private URI parse(String value) {
        try {
            String normalized = value == null ? "" : value.trim();
            if (normalized.length() > 2048) throw new IllegalArgumentException("Document URL must not exceed 2048 characters");
            return URI.create(normalized);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Document URL is invalid", ex);
        }
    }

    private URI appendQuery(URI uri, Map<String, String> queryParams) {
        if (queryParams.isEmpty()) return uri;
        StringBuilder query = new StringBuilder();
        queryParams.forEach((name, value) -> {
            if (!query.isEmpty()) query.append('&');
            query.append(encode(name)).append('=').append(encode(value));
        });
        String source = uri.toASCIIString();
        int fragmentIndex = source.indexOf('#');
        String fragment = fragmentIndex < 0 ? "" : source.substring(fragmentIndex);
        String base = fragmentIndex < 0 ? source : source.substring(0, fragmentIndex);
        URI result = URI.create(base + (uri.getRawQuery() == null ? "?" : "&") + query + fragment);
        if (result.toString().length() > 8192) throw new IllegalArgumentException("Document URL and query must not exceed 8192 characters");
        return result;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void validateHttpUri(URI uri, boolean allowPrivateNetwork) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null
            || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Document URL must be an HTTP or HTTPS address without embedded credentials");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isAlwaysBlocked(address)) {
                    throw new IllegalArgumentException("Document URL must not resolve to a local, link-local, or unsafe address");
                }
                if (!allowPrivateNetwork && isPrivate(address)) {
                    throw new IllegalArgumentException("Document URL resolves to a private address; enable private network access explicitly");
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Document URL host cannot be resolved", ex);
        }
    }

    private boolean isAlwaysBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && ((bytes[0] & 0xff) == 0 || (bytes[0] & 0xff) == 127
            || ((bytes[0] & 0xff) == 169 && (bytes[1] & 0xff) == 254));
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

    private String resolveFileName(URI uri, String contentType, String disposition, String documentTypeHint) {
        String name = dispositionFileName(disposition);
        if (name.isBlank()) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            name = path.substring(path.lastIndexOf('/') + 1);
        }
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (hasSupportedExtension(name)) return name;
        String extension = extensionFor(contentType);
        if (extension == null) extension = extensionForDocumentType(documentTypeHint);
        if (extension == null) throw new IllegalArgumentException("Document URL must return a supported document type");
        return (name.isBlank() ? "remote-document" : name) + extension;
    }

    private String dispositionFileName(String disposition) {
        for (String part : disposition.split(";")) {
            String value = part.trim();
            if (value.toLowerCase(Locale.ROOT).startsWith("filename*=")) {
                String encoded = unquote(value.substring(value.indexOf('=') + 1));
                int marker = encoded.indexOf("''");
                return URLDecoder.decode(marker >= 0 ? encoded.substring(marker + 2) : encoded, StandardCharsets.UTF_8);
            }
            if (value.toLowerCase(Locale.ROOT).startsWith("filename=")) {
                return unquote(value.substring(value.indexOf('=') + 1));
            }
        }
        return "";
    }

    private String unquote(String value) {
        String result = value.trim();
        return result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")
            ? result.substring(1, result.length() - 1) : result;
    }

    private boolean hasSupportedExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private String extensionFor(String rawContentType) {
        String type = rawContentType == null ? "" : rawContentType.toLowerCase(Locale.ROOT);
        if (type.contains("pdf")) return ".pdf";
        if (type.contains("wordprocessingml")) return ".docx";
        if (type.contains("msword")) return ".doc";
        if (type.contains("spreadsheetml")) return ".xlsx";
        if (type.contains("ms-excel")) return ".xls";
        if (type.contains("csv")) return ".csv";
        if (type.contains("markdown")) return ".md";
        if (type.contains("sql")) return ".sql";
        if (type.startsWith("text/")) return ".txt";
        return null;
    }

    private String extensionForDocumentType(String rawDocumentType) {
        String type = rawDocumentType == null ? "" : rawDocumentType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "pdf" -> ".pdf";
            case "word" -> ".docx";
            case "excel" -> ".xlsx";
            case "markdown" -> ".md";
            case "sql" -> ".sql";
            case "text" -> ".txt";
            default -> null;
        };
    }

    private byte[] readBounded(InputStream input) {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxDownloadBytes) throw new IllegalArgumentException("Remote document exceeds the 55MB upload limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read remote document", ex);
        }
    }

    private boolean containsHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }

    private void close(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (IOException ignored) { }
    }

    public record RequestOptions(String method, Map<String, String> queryParams, Map<String, String> headers,
                                 String body, boolean allowPrivateNetwork) {
        public static RequestOptions defaults() {
            return new RequestOptions("GET", Map.of(), Map.of(), "", false);
        }

        RequestOptions forRedirect(URI previous, URI redirected, int status) {
            Map<String, String> nextHeaders = headers;
            if (!sameAuthority(previous, redirected)) {
                Map<String, String> filtered = new LinkedHashMap<>();
                headers.forEach((name, value) -> {
                    if (!name.equalsIgnoreCase("Authorization") && !name.equalsIgnoreCase("Cookie")
                        && !name.equalsIgnoreCase("Proxy-Authorization")) filtered.put(name, value);
                });
                nextHeaders = Map.copyOf(filtered);
            }
            return status == 303
                ? new RequestOptions("GET", Map.of(), nextHeaders, "", allowPrivateNetwork)
                : new RequestOptions(method, Map.of(), nextHeaders, body, allowPrivateNetwork);
        }

        private boolean sameAuthority(URI first, URI second) {
            return first.getScheme() != null && second.getScheme() != null
                && first.getHost() != null && second.getHost() != null
                && first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
        }

        private int effectivePort(URI uri) {
            return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        }
    }

    public record RemoteDocument(String sourceUrl, String fileName, String contentType, byte[] bytes) { }
}
