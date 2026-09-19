package com.chatchat.api.datascience.skill;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Downloads public Markdown/ZIP skill packages without allowing access to internal networks. */
@Component
public class DomainSkillRemoteImporter {
    private static final int MAX_REDIRECTS = 3;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private final HttpClient client;

    public DomainSkillRemoteImporter() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    DomainSkillRemoteImporter(HttpClient client) {
        this.client = client;
    }

    public RemoteFile download(String sourceUrl) {
        URI uri = parse(sourceUrl);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validatePublicHttpUri(uri);
            HttpResponse<InputStream> response = send(uri);
            if (REDIRECTS.contains(response.statusCode())) {
                close(response.body());
                if (redirect == MAX_REDIRECTS) throw new IllegalArgumentException("Skill URL has too many redirects");
                String location = response.headers().firstValue("location")
                    .orElseThrow(() -> new IllegalArgumentException("Skill URL redirect is missing Location"));
                uri = uri.resolve(location);
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

    private HttpResponse<InputStream> send(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Accept", "text/markdown, application/zip, application/octet-stream;q=0.8")
                .header("User-Agent", "ChatChat-SkillImporter/1.0")
                .GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Skill URL download was interrupted", ex);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to download skill URL", ex);
        }
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

    private void validatePublicHttpUri(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null
            || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Skill URL must be a public HTTP or HTTPS address");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!isPublic(address)) {
                    throw new IllegalArgumentException("Skill URL must not resolve to a local or private address");
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Skill URL host cannot be resolved", ex);
        }
    }

    private boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff, second = bytes[1] & 0xff;
            return !(first == 0 || first == 127 || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254));
        }
        return bytes.length != 16 || (bytes[0] & 0xfe) != 0xfc;
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

    public record RemoteFile(String sourceUrl, String fileName, byte[] bytes) { }
}
