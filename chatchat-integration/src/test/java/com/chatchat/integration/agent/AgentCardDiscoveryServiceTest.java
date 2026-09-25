package com.chatchat.integration.agent;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.capability.CapabilityId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCardDiscoveryServiceTest {
    @Test void acceptsPinnedRs256CardAndRejectsTampering() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var pair = keys.generateKeyPair();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode card = (ObjectNode) mapper.readTree("""
            {"name":"signed-agent","description":"test","version":"v1",
             "capabilities":{"streaming":false,"pushNotifications":false},
             "defaultInputModes":["application/json"],"defaultOutputModes":["application/json"],
             "skills":[],"supportedInterfaces":[{"protocolBinding":"HTTP+JSON",
             "url":"http://127.0.0.1:%d","protocolVersion":"1.0"}]}
            """.formatted(port));
        String protectedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"alg\":\"RS256\",\"kid\":\"group-key-1\"}".getBytes(StandardCharsets.UTF_8));
        String payload = new JsonCanonicalizer(mapper.writeValueAsString(card)).getEncodedString();
        String signingInput = protectedHeader + "." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        card.putArray("signatures").addObject().put("protected", protectedHeader)
            .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
        AtomicReference<String> served = new AtomicReference<>(mapper.writeValueAsString(card));
        AtomicReference<String> discoveryQuery = new AtomicReference<>();
        server.createContext("/.well-known/agent-card.json", exchange -> {
            discoveryQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = served.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
            AgentDescriptor descriptor = new AgentDescriptor("signed-agent", "v1", AgentDescriptor.Origin.GROUP,
                AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("http://127.0.0.1:" + port),
                Set.of(CapabilityId.parse("finance.test.v1")), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
                AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of(), null, "", 1, true,
                Map.of("requireSignedCard", true, "cardKeyId", "group-key-1", "cardPublicKeyPem", pem,
                    "requestQueryParameters", Map.of("region", "north")));
            var discovery = new AgentCardDiscoveryService(mapper);
            assertThat(discovery.discoverSummary(descriptor, null).signatureVerified()).isTrue();
            assertThat(discoveryQuery.get()).isEqualTo("region=north");
            assertThat(discovery.discover(descriptor, null).supportedInterfaces().get(0).url())
                .isEqualTo(descriptor.endpoint().toString());
            served.set(served.get().replace("signed-agent", "tampered-agent"));
            assertThatThrownBy(() -> discovery.discover(descriptor, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
        } finally { server.stop(0); }
    }
}
