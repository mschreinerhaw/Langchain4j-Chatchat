package com.chatchat.mcpserver.market;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalRequestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketInternalAuthFilterModelSyncTest {
    @Test
    void modelSyncRequiresSignedInternalRequest() throws Exception {
        InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
        when(credentials.isEnabled()).thenReturn(true);
        when(credentials.resolvedUsername()).thenReturn("internal");
        when(credentials.resolvedSecret()).thenReturn("test-secret");
        MarketInternalAuthFilter filter = new MarketInternalAuthFilter(credentials);
        String path = "/internal/v1/models/embeddings";

        MockHttpServletRequest unsigned = new MockHttpServletRequest("PUT", path);
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(unsigned, denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(401);

        MockHttpServletRequest signed = new MockHttpServletRequest("PUT", path);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "model-sync-nonce-123456789";
        signed.addHeader(InternalRequestSigner.USER_HEADER, "internal");
        signed.addHeader(InternalRequestSigner.TIMESTAMP_HEADER, timestamp);
        signed.addHeader(InternalRequestSigner.NONCE_HEADER, nonce);
        signed.addHeader(InternalRequestSigner.SIGNATURE_HEADER,
            InternalRequestSigner.sign("test-secret", "PUT", path, timestamp, nonce));
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter.doFilter(signed, allowed, new MockFilterChain());
        assertThat(allowed.getStatus()).isEqualTo(200);
    }
}
