package com.chatchat.api.controller.search;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalDocumentSearchAuthFilterTest {
    @Test
    void requiresSignedRequestAndRejectsReplay() throws Exception {
        InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
        when(credentials.isEnabled()).thenReturn(true);
        when(credentials.resolvedUsername()).thenReturn("service-user");
        when(credentials.resolvedSecret()).thenReturn("test-secret");
        InternalDocumentSearchAuthFilter filter = new InternalDocumentSearchAuthFilter(credentials);

        MockHttpServletResponse unsigned = new MockHttpServletResponse();
        filter.doFilter(request(null, null), unsigned, new MockFilterChain());
        assertThat(unsigned.getStatus()).isEqualTo(401);

        String nonce = UUID.randomUUID().toString();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(request(timestamp, nonce), accepted, new MockFilterChain());
        assertThat(accepted.getStatus()).isEqualTo(200);

        MockHttpServletResponse replay = new MockHttpServletResponse();
        filter.doFilter(request(timestamp, nonce), replay, new MockFilterChain());
        assertThat(replay.getStatus()).isEqualTo(401);
    }

    @Test
    void protectsResourceAuthorizationEndpoint() throws Exception {
        InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
        when(credentials.isEnabled()).thenReturn(true);
        when(credentials.resolvedUsername()).thenReturn("service-user");
        when(credentials.resolvedSecret()).thenReturn("test-secret");
        InternalDocumentSearchAuthFilter filter = new InternalDocumentSearchAuthFilter(credentials);
        String path = "/internal/v1/resource-authorization";
        String nonce = UUID.randomUUID().toString();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        MockHttpServletResponse unsigned = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", path), unsigned, new MockFilterChain());
        assertThat(unsigned.getStatus()).isEqualTo(401);

        MockHttpServletRequest signed = new MockHttpServletRequest("POST", path);
        signed.addHeader(InternalRequestSigner.USER_HEADER, "service-user");
        signed.addHeader(InternalRequestSigner.TIMESTAMP_HEADER, timestamp);
        signed.addHeader(InternalRequestSigner.NONCE_HEADER, nonce);
        signed.addHeader(InternalRequestSigner.SIGNATURE_HEADER,
            InternalRequestSigner.sign("test-secret", "POST", path, timestamp, nonce));
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(signed, accepted, new MockFilterChain());
        assertThat(accepted.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request(String timestamp, String nonce) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/document-search");
        if (timestamp != null) {
            request.addHeader(InternalRequestSigner.USER_HEADER, "service-user");
            request.addHeader(InternalRequestSigner.TIMESTAMP_HEADER, timestamp);
            request.addHeader(InternalRequestSigner.NONCE_HEADER, nonce);
            request.addHeader(InternalRequestSigner.SIGNATURE_HEADER,
                InternalRequestSigner.sign("test-secret", "POST", "/internal/v1/document-search", timestamp, nonce));
        }
        return request;
    }
}
