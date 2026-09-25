package com.chatchat.integration.agent;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.agent.*;
import com.chatchat.agents.runtime.federation.RemoteAgentEvidenceProjector;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.evidence.ToolAnalysisEvidence;
import com.chatchat.common.runtime.capability.CapabilityId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class A2aHttpJsonAgentGatewayTest {
    @Test void resumesInputRequiredTaskWithOriginalTaskAndContextIds() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger sends = new AtomicInteger();
        AtomicReference<String> resumedBody = new AtomicReference<>();
        server.createContext("/.well-known/agent-card.json", exchange -> reply(exchange, """
            {"name":"group-test","description":"test","version":"v1",
             "capabilities":{"streaming":false,"pushNotifications":false},
             "defaultInputModes":["application/json"],"defaultOutputModes":["application/json"],
             "skills":[],"supportedInterfaces":[{"protocolBinding":"HTTP+JSON",
             "url":"http://127.0.0.1:%d","protocolVersion":"1.0"}]}
            """.formatted(port)));
        server.createContext("/message:send", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (sends.incrementAndGet() == 1) reply(exchange, """
                {"task":{"id":"task-1","contextId":"ctx-1","status":{"state":"TASK_STATE_INPUT_REQUIRED",
                 "message":{"messageId":"reply-1","role":"ROLE_AGENT","parts":[{"data":{
                   "schemaVersion":"agent_execution_outcome.v1","executionId":"exec-resume",
                   "providerAgentId":"group-test","status":"SUPPLEMENT_EVIDENCE",
                   "missingEvidence":[{"type":"RULE_LOOKUP","required":true,"minimumCount":1}]}}]}}}}
                """);
            else {
                resumedBody.set(body);
                reply(exchange, """
                    {"task":{"id":"task-1","contextId":"ctx-1","status":{"state":"TASK_STATE_COMPLETED"},
                     "artifacts":[{"artifactId":"a-1","parts":[{"data":{
                       "schemaVersion":"agent_execution_outcome.v1","executionId":"exec-resume",
                       "providerAgentId":"group-test","status":"COMPLETED",
                       "claims":[{"claimId":"C1","text":"verified","evidenceIds":[],"confidence":0.9}]}}]}]}}
                    """);
            }
        });
        server.start();
        try {
            @SuppressWarnings("unchecked") ObjectProvider<AgentCredentialResolver> credentials = mock(ObjectProvider.class);
            var gateway = new A2aHttpJsonAgentGateway(WebClient.builder(), new ObjectMapper(), credentials,
                new RemoteAgentEvidenceProjector(new ObjectMapper()));
            CapabilityId capability = CapabilityId.parse("finance.test.v1");
            AgentDescriptor agent = new AgentDescriptor("group-test", "v1", AgentDescriptor.Origin.GROUP,
                AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("http://127.0.0.1:" + port),
                Set.of(capability), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
                AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of(), null, "", 1, true, Map.of());
            AgentExecutionRequest request = new AgentExecutionRequest(null, "exec-resume", capability,
                new AgentExecutionRequest.TaskContract("test", "analyze", Map.of()), EvidenceBundle.empty("test"),
                Set.of(), new AgentExecutionRequest.Constraints(3000, 2, false, false, Set.of()), null,
                new KernelDataScope("tenant", "user", "request", "conversation", "run", "test", Map.of()), Map.of());

            assertThat(gateway.invoke(agent, request).status()).isEqualTo(AgentExecutionOutcome.Status.SUPPLEMENT_EVIDENCE);
            assertThat(gateway.resume(agent, request).status()).isEqualTo(AgentExecutionOutcome.Status.COMPLETED);
            assertThat(resumedBody.get()).contains("task-1", "ctx-1");
            assertThat(sends.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }
    @Test void cancelsOutstandingTaskThroughSdk() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger cancellationCalls = new AtomicInteger();
        server.createContext("/.well-known/agent-card.json", exchange -> reply(exchange, """
            {"name":"group-test","description":"test","version":"v1",
             "capabilities":{"streaming":false,"pushNotifications":false},
             "defaultInputModes":["application/json"],"defaultOutputModes":["application/json"],
             "skills":[],"supportedInterfaces":[{"protocolBinding":"HTTP+JSON",
             "url":"http://127.0.0.1:%d","protocolVersion":"1.0"}]}
            """.formatted(port)));
        server.createContext("/message:send", exchange -> reply(exchange,
            "{\"task\":{\"id\":\"task-1\",\"contextId\":\"ctx-1\",\"status\":{\"state\":\"TASK_STATE_SUBMITTED\"}}}"));
        server.createContext("/tasks/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith(":cancel")) {
                cancellationCalls.incrementAndGet();
                reply(exchange, "{\"id\":\"task-1\",\"contextId\":\"ctx-1\",\"status\":{\"state\":\"TASK_STATE_CANCELED\"}}");
            } else reply(exchange,
                "{\"id\":\"task-1\",\"contextId\":\"ctx-1\",\"status\":{\"state\":\"TASK_STATE_WORKING\"}}");
        });
        server.start();
        try {
            @SuppressWarnings("unchecked") ObjectProvider<AgentCredentialResolver> credentials = mock(ObjectProvider.class);
            A2aHttpJsonAgentGateway gateway = new A2aHttpJsonAgentGateway(WebClient.builder(),
                new ObjectMapper(), credentials, new RemoteAgentEvidenceProjector(new ObjectMapper()));
            CapabilityId capability = CapabilityId.parse("finance.test.v1");
            AgentDescriptor agent = new AgentDescriptor("group-test", "v1", AgentDescriptor.Origin.GROUP,
                AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("http://127.0.0.1:" + port),
                Set.of(capability), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
                AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of(), null, "", 1, true, Map.of());
            AgentExecutionRequest request = new AgentExecutionRequest(null, "exec-cancel", capability,
                new AgentExecutionRequest.TaskContract("test", "analyze", Map.of()), EvidenceBundle.empty("test"),
                Set.of(), new AgentExecutionRequest.Constraints(1000, 1, true, true, Set.of()), null,
                new KernelDataScope("tenant", "user", "request", "conversation", "run", "test", Map.of()), Map.of());

            AgentExecutionOutcome timedOut = gateway.invoke(agent, request);
            assertThat(timedOut.status()).as(timedOut.toString()).isEqualTo(AgentExecutionOutcome.Status.TIMED_OUT);
            assertThat(gateway.cancel(agent, request.executionId()).status())
                .isEqualTo(AgentExecutionOutcome.Status.CANCELLED);
            assertThat(cancellationCalls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test void sendsProjectedContractThroughSdkRestTransport() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        AtomicReference<String> sent = new AtomicReference<>();
        server.createContext("/.well-known/agent-card.json", exchange -> {
            String card = """
                {"name":"group-test","description":"test","version":"v1",
                 "capabilities":{"streaming":false,"pushNotifications":false},
                 "defaultInputModes":["application/json"],"defaultOutputModes":["application/json"],
                 "skills":[],"supportedInterfaces":[{"protocolBinding":"HTTP+JSON",
                 "url":"http://127.0.0.1:%d","protocolVersion":"1.0"}]}
                """.formatted(port);
            reply(exchange, card);
        });
        server.createContext("/message:send", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, """
                {"task":{"id":"task-1","contextId":"ctx-1","status":{"state":"TASK_STATE_COMPLETED"},
                 "artifacts":[{"artifactId":"artifact-1","parts":[{"data":{
                   "schemaVersion":"agent_execution_outcome.v1","executionId":"exec-1",
                   "providerAgentId":"group-test","status":"COMPLETED",
                   "claims":[{"claimId":"C1","text":"grounded","evidenceIds":["E1"],"confidence":0.9}]
                 }}]}]}}
                """);
        });
        server.start();
        try {
            @SuppressWarnings("unchecked") ObjectProvider<AgentCredentialResolver> credentials = mock(ObjectProvider.class);
            A2aHttpJsonAgentGateway gateway = new A2aHttpJsonAgentGateway(WebClient.builder(),
                new ObjectMapper(), credentials, new RemoteAgentEvidenceProjector(new ObjectMapper()));
            CapabilityId capability = CapabilityId.parse("finance.test.v1");
            AgentDescriptor agent = new AgentDescriptor("group-test", "v1", AgentDescriptor.Origin.GROUP,
                AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("http://127.0.0.1:" + port),
                Set.of(capability), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
                AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of(), null, "", 1, true, Map.of());
            EvidenceBundle evidence = new EvidenceBundle(null,
                java.util.List.of(new ToolAnalysisEvidence("E1", "position", "call-1", "RAW-PRIVATE",
                    Map.of("remoteProjection", Map.of("positionCount", 3)))), java.util.List.of(), Map.of());
            AgentExecutionRequest request = new AgentExecutionRequest(null, "exec-1", capability,
                new AgentExecutionRequest.TaskContract("test", "analyze", Map.of()), evidence,
                Set.of(), new AgentExecutionRequest.Constraints(5000, 1, true, true, Set.of()), null,
                new KernelDataScope("tenant", "sensitive-user", "request", "conversation", "run", "test", Map.of()),
                Map.of("internalMarker", "never-send"));

            AgentExecutionOutcome outcome = gateway.invoke(agent, request);

            assertThat(outcome.status()).isEqualTo(AgentExecutionOutcome.Status.COMPLETED);
            assertThat(outcome.claims()).hasSize(1);
            assertThat(sent.get()).contains("exec-1", "positionCount")
                .doesNotContain("sensitive-user", "never-send", "RAW-PRIVATE");
        } finally {
            server.stop(0);
        }
    }

    @Test void rejectsAgentCardRedirectingCallsAwayFromRegisteredEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger agentCalls = new AtomicInteger();
        int port = server.getAddress().getPort();
        server.createContext("/.well-known/agent-card.json", exchange -> {
            String card = """
                {"name":"group-test","description":"test","version":"v1",
                 "capabilities":{"streaming":false,"pushNotifications":false},
                 "defaultInputModes":["application/json"],"defaultOutputModes":["application/json"],
                 "skills":[],"supportedInterfaces":[{"protocolBinding":"HTTP+JSON",
                 "url":"http://127.0.0.1:%d/unapproved","protocolVersion":"1.0"}]}
                """.formatted(port);
            byte[] bytes = card.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.createContext("/message:send", exchange -> {
            agentCalls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            @SuppressWarnings("unchecked") ObjectProvider<AgentCredentialResolver> credentials = mock(ObjectProvider.class);
            A2aHttpJsonAgentGateway gateway = new A2aHttpJsonAgentGateway(WebClient.builder(),
                new ObjectMapper(), credentials, new RemoteAgentEvidenceProjector(new ObjectMapper()));
            CapabilityId capability = CapabilityId.parse("finance.test.v1");
            AgentDescriptor agent = new AgentDescriptor("group-test", "v1", AgentDescriptor.Origin.GROUP,
                AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("http://127.0.0.1:" + port),
                Set.of(capability), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
                AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of(), null, "", 1, true, Map.of());
            AgentExecutionRequest request = new AgentExecutionRequest(null, "exec-1", capability,
                new AgentExecutionRequest.TaskContract("test", "analyze", Map.of()), EvidenceBundle.empty("test"),
                Set.of(), new AgentExecutionRequest.Constraints(3000, 1, true, true, Set.of()), null,
                new KernelDataScope("tenant", "user", "request", "conversation", "run", "test", Map.of()), Map.of());

            AgentExecutionOutcome outcome = gateway.invoke(agent, request);

            assertThat(outcome.status()).isEqualTo(AgentExecutionOutcome.Status.FAILED);
            assertThat(outcome.errorCode()).isEqualTo("AGENT_CARD_MISMATCH");
            assertThat(agentCalls.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    private void reply(com.sun.net.httpserver.HttpExchange exchange, String content) throws java.io.IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/a2a+json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var body = exchange.getResponseBody()) { body.write(bytes); }
    }
}
