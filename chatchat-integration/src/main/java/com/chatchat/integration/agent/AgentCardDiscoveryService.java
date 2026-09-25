package com.chatchat.integration.agent;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentCardDiscoveryPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.spec.AgentCard;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** Fetches one immutable Card body and verifies JCS/JWS against an operator-pinned key. */
@Component
public class AgentCardDiscoveryService implements AgentCardDiscoveryPort {
    private final ObjectMapper mapper;

    public AgentCardDiscoveryService(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public CardSummary discoverSummary(AgentDescriptor agent, String bearerToken) {
        AgentCard card = discover(agent, bearerToken);
        return new CardSummary(card.name(), card.version(), card.skills() == null ? java.util.List.of()
            : card.skills().stream().map(skill -> skill.id()).toList(),
            card.signatures() != null && !card.signatures().isEmpty(), agent.endpoint().toString());
    }

    public AgentCard discover(AgentDescriptor agent, String bearerToken) {
        if (agent.protocol() != AgentDescriptor.Protocol.A2A_HTTP_JSON)
            throw new IllegalArgumentException("Agent Card requires A2A_HTTP_JSON protocol");
        try {
            URI base = agent.endpoint();
            URI uri = new URI(base.getScheme(), null, base.getHost(), base.getPort(),
                "/.well-known/agent-card.json", null, null);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET();
            if (bearerToken != null) request.header("Authorization", "Bearer " + bearerToken);
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build().send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body().length() > 1_000_000)
                throw new IllegalArgumentException("Agent Card unavailable or exceeds size limit");
            JsonNode cardJson = mapper.readTree(response.body());
            verify(agent, cardJson);
            JsonNode sdkJson = cardJson.deepCopy();
            JsonNode sdkSignatures = sdkJson.path("signatures");
            if (sdkSignatures.isArray()) for (JsonNode signature : sdkSignatures) {
                if (signature instanceof com.fasterxml.jackson.databind.node.ObjectNode object
                    && object.has("protected"))
                    object.set("protectedHeader", object.remove("protected"));
            }
            AgentCard card = mapper.treeToValue(sdkJson, AgentCard.class);
            if (card.supportedInterfaces() == null || card.supportedInterfaces().stream().noneMatch(value ->
                "HTTP+JSON".equalsIgnoreCase(value.protocolBinding())
                    && base.normalize().equals(URI.create(value.url()).normalize())))
                throw new IllegalArgumentException("Agent Card endpoint differs from registered endpoint");
            return card;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent Card discovery interrupted", interrupted);
        } catch (Exception error) {
            if (error instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalStateException("Agent Card discovery or signature verification failed", error);
        }
    }

    private void verify(AgentDescriptor agent, JsonNode card) throws Exception {
        JsonNode signatures = card.path("signatures");
        boolean signed = signatures.isArray() && !signatures.isEmpty();
        boolean required = Boolean.TRUE.equals(agent.metadata().get("requireSignedCard"))
            || !isLoopback(agent.endpoint());
        if (!signed) {
            if (required) throw new IllegalArgumentException("Signed Agent Card is required");
            return;
        }
        Object pemValue = agent.metadata().get("cardPublicKeyPem");
        Object kidValue = agent.metadata().get("cardKeyId");
        if (!(pemValue instanceof String pem) || pem.isBlank()
            || !(kidValue instanceof String expectedKid) || expectedKid.isBlank())
            throw new IllegalArgumentException("Signed Agent Card requires pinned cardPublicKeyPem and cardKeyId");
        JsonNode payload = card.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).remove("signatures");
        String canonical = new JsonCanonicalizer(mapper.writeValueAsString(payload)).getEncodedString();
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            canonical.getBytes(StandardCharsets.UTF_8));
        byte[] keyBytes = Base64.getMimeDecoder().decode(pem.replaceAll("-----[^-]+-----", ""));
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
        for (JsonNode entry : signatures) {
            String protectedValue = entry.path("protected").asText();
            if (protectedValue.isBlank()) continue;
            JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(protectedValue));
            if (!"RS256".equals(header.path("alg").asText())
                || !expectedKid.equals(header.path("kid").asText())) continue;
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((protectedValue + "." + encodedPayload).getBytes(StandardCharsets.US_ASCII));
            if (verifier.verify(Base64.getUrlDecoder().decode(entry.path("signature").asText()))) return;
        }
        throw new IllegalArgumentException("Agent Card JWS signature is invalid or untrusted");
    }

    private boolean isLoopback(URI endpoint) {
        return "localhost".equalsIgnoreCase(endpoint.getHost())
            || "127.0.0.1".equals(endpoint.getHost()) || "::1".equals(endpoint.getHost());
    }
}
