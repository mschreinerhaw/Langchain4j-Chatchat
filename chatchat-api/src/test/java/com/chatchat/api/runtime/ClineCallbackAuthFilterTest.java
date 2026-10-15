package com.chatchat.api.runtime;

import com.chatchat.common.security.InternalRequestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class ClineCallbackAuthFilterTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String PATH = "/internal/coding/cline/model";

    @Test void springCreatesFilterFromItsProductionConstructorWhenCodingIsEnabled() {
        new ApplicationContextRunner()
            .withPropertyValues("chatchat.coding.enabled=true", "chatchat.coding.cline.enabled=true",
                "chatchat.coding.cline.shared-secret=" + SECRET,
                "chatchat.coding.cline.allowed-peer-addresses=172.30.91.11")
            .withUserConfiguration(ClineCallbackAuthFilter.class)
            .run(context -> assertThat(context).hasSingleBean(ClineCallbackAuthFilter.class));
    }

    @Test void acceptsOneSignedLoopbackCallbackAndRejectsReplay() throws Exception {
        var filter = new ClineCallbackAuthFilter(SECRET);
        var request = signed("127.0.0.1");
        var first = new MockHttpServletResponse();
        var reached = new AtomicBoolean();
        filter.doFilter(request, first, (req, res) -> reached.set(true));
        assertThat(reached).isTrue();
        assertThat(first.getStatus()).isEqualTo(200);
        var replay = new MockHttpServletResponse();
        filter.doFilter(request, replay, (req, res) -> fail("replay reached controller"));
        assertThat(replay.getStatus()).isEqualTo(401);
    }

    @Test void rejectsRemoteAndTamperedCallback() throws Exception {
        var filter = new ClineCallbackAuthFilter(SECRET);
        var remote = new MockHttpServletResponse();
        filter.doFilter(signed("192.0.2.10"), remote, (req, res) -> fail("remote reached controller"));
        assertThat(remote.getStatus()).isEqualTo(401);
        var request = signed("127.0.0.1");
        request.setRequestURI("/internal/coding/cline/finish");
        var tampered = new MockHttpServletResponse();
        filter.doFilter(request, tampered, (req, res) -> fail("tampered request reached controller"));
        assertThat(tampered.getStatus()).isEqualTo(401);
    }

    @Test void acceptsOnlyConfiguredContainerAddress() throws Exception {
        var filter = new ClineCallbackAuthFilter(SECRET, "127.0.0.1,172.30.91.10");
        var accepted = new MockHttpServletResponse();
        var reached = new AtomicBoolean();
        filter.doFilter(signed("172.30.91.10"), accepted, (req, res) -> reached.set(true));
        assertThat(reached).isTrue();
        var denied = new MockHttpServletResponse();
        filter.doFilter(signed("172.30.91.11"), denied,
            (req, res) -> fail("unlisted container reached controller"));
        assertThat(denied.getStatus()).isEqualTo(401);
    }

    @Test void authenticatesBodyAndPreservesItForController() throws Exception {
        var filter = new ClineCallbackAuthFilter(SECRET);
        var request = signed("127.0.0.1");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
            assertThat(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("{\"taskId\":\"authorized\"}"));
        var tampered = signed("127.0.0.1");
        tampered.setContent("{\"taskId\":\"another-task\"}".getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        filter.doFilter(tampered, response, (req, res) -> fail("changed body reached controller"));
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test void rejectsLegacySignatureAndOversizedPayload() throws Exception {
        var filter = new ClineCallbackAuthFilter(SECRET);
        var legacy = signed("127.0.0.1");
        legacy.removeHeader("X-Chatchat-Signature-Version");
        var response = new MockHttpServletResponse();
        filter.doFilter(legacy, response, (req, res) -> fail("legacy reached controller"));
        assertThat(response.getStatus()).isEqualTo(401);
        var oversized = signed("127.0.0.1");
        oversized.setContent(new byte[1024 * 1024 + 1]);
        var large = new MockHttpServletResponse();
        filter.doFilter(oversized, large, (req, res) -> fail("oversized body reached controller"));
        assertThat(large.getStatus()).isEqualTo(413);
    }

    private static MockHttpServletRequest signed(String address) {
        var request = new MockHttpServletRequest("POST", PATH);
        request.setRemoteAddr(address);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        request.addHeader("Authorization", "Bearer " + SECRET);
        request.addHeader(InternalRequestSigner.TIMESTAMP_HEADER, timestamp);
        request.addHeader(InternalRequestSigner.NONCE_HEADER, nonce);
        byte[] body = "{\"taskId\":\"authorized\"}".getBytes(StandardCharsets.UTF_8);
        request.setContent(body);
        request.addHeader("X-Chatchat-Signature-Version", "2");
        request.addHeader(InternalRequestSigner.SIGNATURE_HEADER,
            InternalRequestSigner.signBody(SECRET, "POST", PATH, timestamp, nonce, body));
        return request;
    }
}
