package com.chatchat.api.search;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import org.mockito.ArgumentCaptor;

class DocumentRemoteImporterTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void downloadsPrivateDocumentWithAdvancedRequestWhenExplicitlyAllowed() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse response = mock(HttpResponse.class);
        byte[] content = "# Internal report".getBytes(StandardCharsets.UTF_8);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(
            "content-type", List.of("text/markdown"),
            "content-disposition", List.of("attachment; filename=research.md")), (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(content));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        var options = new DocumentRemoteImporter.RequestOptions("POST", Map.of("version", "latest release"),
            Map.of("Authorization", "Bearer token"), "{\"id\":1}", true);
        var document = new DocumentRemoteImporter(client, 55L * 1024 * 1024)
            .download("http://10.20.30.40/document", options);

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().uri().toString()).contains("version=latest%20release");
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("Bearer token");
        assertThat(document.fileName()).isEqualTo("research.md");
        assertThat(document.bytes()).isEqualTo(content);
    }

    @Test
    void rejectsPrivateDocumentAddressWithoutExplicitOptIn() throws Exception {
        HttpClient client = mock(HttpClient.class);
        DocumentRemoteImporter importer = new DocumentRemoteImporter(client, 1024);

        assertThatThrownBy(() -> importer.download("http://10.20.30.40/report.pdf",
            DocumentRemoteImporter.RequestOptions.defaults()))
            .hasMessageContaining("enable private network access");
        verify(client, never()).send(any(), any());
    }

    @Test
    void alwaysRejectsLoopbackEvenWhenPrivateNetworkIsAllowed() throws Exception {
        HttpClient client = mock(HttpClient.class);
        DocumentRemoteImporter importer = new DocumentRemoteImporter(client, 1024);
        var options = new DocumentRemoteImporter.RequestOptions("GET", Map.of(), Map.of(), "", true);

        assertThatThrownBy(() -> importer.download("http://127.0.0.1/report.pdf", options))
            .hasMessageContaining("local, link-local, or unsafe");
        verify(client, never()).send(any(), any());
    }
}
