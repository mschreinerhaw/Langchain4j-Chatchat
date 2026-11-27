package com.chatchat.api.enterprise.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class ReportPrintClient {
    private static final int MAX_PDF_BYTES = 12 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String url;
    private final String token;

    ReportPrintClient(ObjectMapper json, @Value("${chatchat.report-export.renderer-url:}") String url,
                      @Value("${chatchat.report-export.renderer-token:}") String token) {
        this.json = json;
        this.url = url;
        this.token = token;
    }

    byte[] print(ReportExportSource.Document document) throws IOException {
        if (url.isBlank() || token.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "报告渲染服务未配置");
        var request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(50)).header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(document))).build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() == 429)
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "报告渲染服务繁忙");
                if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("").startsWith("application/pdf"))
                    throw new IOException("Report renderer rejected the document");
                byte[] bytes = stream.readNBytes(MAX_PDF_BYTES + 1);
                if (bytes.length > MAX_PDF_BYTES) throw new IOException("Renderer PDF exceeds bounds");
                return bytes;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Report rendering interrupted", e);
        }
    }
}
