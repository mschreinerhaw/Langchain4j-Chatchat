package com.chatchat.integration.mcp.service.client;

import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.integration.mcp.config.McpCenterProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

class McpPythonControlPlaneClientTimeoutTest {
    @Test void unicodeVersionedCodePathsAreEncodedExactlyOnceForBytesAndStreamingDownloads() throws Exception {
        String file="销售汇总_20261022T080000000Z_abcdef012345_v2.py";
        byte[] code="print(42)\n".getBytes(StandardCharsets.UTF_8);
        var paths=new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/admin/auth/login",exchange->{exchange.getRequestBody().readAllBytes();respond(exchange,"{\"code\":200,\"data\":{\"token\":\"internal-token\"}}");});
        server.createContext("/api/v1/python/runtime/operations",exchange->{
            paths.add(exchange.getRequestURI().getRawPath());
            if(!exchange.getRequestURI().getPath().endsWith("/code/"+file)){exchange.sendResponseHeaders(404,-1);exchange.close();return;}
            exchange.sendResponseHeaders(200,code.length);try(var output=exchange.getResponseBody()){output.write(code);}
        });
        server.start();
        try {
            var properties=new McpCenterProperties();properties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            var credentials=mock(InternalCredentialProperties.class);when(credentials.resolvedUsername()).thenReturn("internal");
            when(credentials.resolvedSecret()).thenReturn("0123456789abcdef0123456789abcdef");
            when(credentials.resolveSecret(nullable(String.class),nullable(String.class))).thenReturn("");
            var client=new McpPythonControlPlaneClient(properties,credentials,new ObjectMapper());
            assertArrayEquals(code,client.artifact("tenant","owner","run","task","op","code",file));
            var output=new java.io.ByteArrayOutputStream();client.downloadArtifact("tenant","owner","run","task","op","code",file,output);
            assertArrayEquals(code,output.toByteArray());assertEquals(2,paths.size());
            assertTrue(paths.stream().allMatch(path->path.contains("%E9")&&!path.contains("%25")));
        } finally {server.stop(0);}
    }
    @Test void emptyArchivedArtifactIsARealZeroByteResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/admin/auth/login", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, "{\"code\":200,\"data\":{\"token\":\"internal-token\"}}");
        });
        server.createContext("/api/v1/python/runtime/operations/op/artifacts/logs/stderr.txt", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            McpCenterProperties properties = new McpCenterProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
            when(credentials.resolvedUsername()).thenReturn("internal");
            when(credentials.resolvedSecret()).thenReturn("0123456789abcdef0123456789abcdef");
            when(credentials.resolveSecret(nullable(String.class), nullable(String.class))).thenReturn("");
            var client = new McpPythonControlPlaneClient(properties, credentials,
                    new ObjectMapper().findAndRegisterModules());
            assertArrayEquals(new byte[0], client.artifact("tenant", "owner", "run", "task",
                    "op", "logs", "stderr.txt"));
        } finally { server.stop(0); }
    }

    @Test void lostStartResponseCanBeQueriedWithoutAnotherStart() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/v1/admin/auth/login", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, "{\"code\":200,\"data\":{\"token\":\"internal-token\"}}");
        });
        server.createContext("/api/v1/python/runtime/operations", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!body.contains("sourceCiphertext") || body.contains("print(123)")) throw new AssertionError("Source was not encrypted");
                accepted.incrementAndGet();
                try { Thread.sleep(2500); respond(exchange, "{\"code\":200,\"data\":null}"); }
                catch (Exception ignored) { exchange.close(); }
            } else {
                String status = accepted.get() == 0 ? "CREATED" : "RUNNING";
                respond(exchange, "{\"code\":200,\"data\":{\"operationId\":\"op\",\"status\":\"" + status + "\",\"containerId\":\"container\",\"fencingToken\":1,\"cancelRequested\":false,\"executorStopped\":false,\"exitCode\":null,\"codeHash\":\"hash\",\"imageDigest\":\"digest\",\"manifestHash\":null,\"failure\":null}}");
            }
        });
        server.start();
        try {
            McpCenterProperties properties = new McpCenterProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setTimeoutMs(1000);
            InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
            when(credentials.resolvedUsername()).thenReturn("internal");
            when(credentials.resolvedSecret()).thenReturn("0123456789abcdef0123456789abcdef");
            when(credentials.resolveSecret(nullable(String.class), nullable(String.class))).thenReturn("");
            var client = new McpPythonControlPlaneClient(properties, credentials, new ObjectMapper().findAndRegisterModules());
            assertEquals("CREATED", client.get("tenant", "owner", "run", "task", "op").status());
            var request = new McpPythonControlPlanePort.StartRequest("tenant", "owner", "run", "task", "op", 1, 1,
                    "env", "asset", "main.py", "print(123)", Map.of(), "{}", List.of(), Map.of(),
                    "a".repeat(64), Instant.now().plusSeconds(60), "reservation");
            assertThrows(RuntimeException.class, () -> client.start(request));
            assertEquals("RUNNING", client.get("tenant", "owner", "run", "task", "op").status());
            assertEquals(1, accepted.get());
        } finally { server.stop(0); executor.shutdownNow(); }
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
