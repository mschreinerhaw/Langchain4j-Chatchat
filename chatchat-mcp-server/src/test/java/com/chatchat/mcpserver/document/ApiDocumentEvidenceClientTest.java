package com.chatchat.mcpserver.document;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import com.chatchat.knowledgebase.search.document.DocumentSearchRequest;
import com.chatchat.knowledgebase.search.document.DocumentSearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiDocumentEvidenceClientTest {
    @Test
    void callsApiCorpusWithInternalSignatureAndReadsEvidenceContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AtomicReference<String> signature = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/document-search", exchange -> {
            signature.set(exchange.getRequestHeaders().getFirst(InternalRequestSigner.SIGNATURE_HEADER));
            byte[] response = mapper.writeValueAsBytes(ApiResponse.success(
                new DocumentSearchResult("document_evidence_v1", "livedata", "how_to", 0,
                    List.of(), "", List.of())));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
            when(credentials.isEnabled()).thenReturn(true);
            when(credentials.resolvedSecret()).thenReturn("test-secret");
            when(credentials.resolvedUsername()).thenReturn("service-user");
            MockEnvironment environment = new MockEnvironment().withProperty(
                "chatchat.mcp.authorization.api-base-url", "http://127.0.0.1:" + server.getAddress().getPort());
            ApiDocumentEvidenceClient client = new ApiDocumentEvidenceClient(environment, credentials, mapper);
            DocumentSearchRequest request = new DocumentSearchRequest("livedata", 8, List.of(), null,
                "tenant-1", "user-1", List.of(), false);

            assertThat(client.search(request)).isPresent().get()
                .extracting(DocumentSearchResult::contractVersion).isEqualTo("document_evidence_v1");
            assertThat(signature.get()).isNotBlank();
        } finally {
            server.stop(0);
        }
    }
}
