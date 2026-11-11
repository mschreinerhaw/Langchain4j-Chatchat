package com.chatchat.api.runtime.coding;

import com.chatchat.api.runtime.coding.nativecline.NativeS3NodeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NativeS3NodeClientTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void actualDeliveryProtocolRejectionIsAvailableForSameSessionFeedback()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            byte[] body="{\"code\":\"DELIVERY_SELECTION_INVALID\",\"message\":\"Invalid artifact type at https://silo/private?signature=secret\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try{
            var client=new NativeS3NodeClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),json);
            assertThatThrownBy(()->client.preview("signed.grant","session","v1"))
                .isInstanceOfSatisfying(NativeS3NodeClient.NodeException.class,e->{
                    assertThat(e.protocolCode()).isEqualTo("DELIVERY_SELECTION_INVALID");
                    assertThat(e.validationFeedback()).contains("Invalid artifact type").doesNotContain("signature","secret","https://");
                });
        }finally{server.stop(0);}
    }
    @Test void candidateContentCannotChangeAfterItsDeclaredHash()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            byte[] body="{\"path\":\"results.json\",\"data\":\"dGFtcGVyZWQ=\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try{
            var client=new NativeS3NodeClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),json);
            var candidate=json.readTree("{\"deliveryVersion\":\"v1\",\"artifacts\":[{\"path\":\"results.json\",\"size\":8,\"sha256\":\""+"a".repeat(64)+"\"}]}");
            assertThatThrownBy(()->client.previewContent("signed.grant","session",candidate,"results.json"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity");
            assertThatThrownBy(()->client.previewContent("signed.grant","session",candidate,"other-tenant.json"))
                .isInstanceOf(IllegalArgumentException.class);
        }finally{server.stop(0);}
    }
    @Test void inputInventoryMatchesNodeByteHashAndIncludesExactObjectPath(){
        var client=new NativeS3NodeClient(URI.create("http://127.0.0.1:18338"),json);
        var input=new NativeS3NodeClient.Input("input/data.csv",4,"a".repeat(64),"v1","https://silo.example/bucket/tenant/task/input/data.csv?versionId=v1");
        assertThat(client.inputManifestHash(List.of(input))).isEqualTo("29787871f079a54fec56968ebd98ea9571a15eeec0080f30f043f289a2c25a8c");
        var refreshed=new NativeS3NodeClient.Input(input.path(),input.size(),input.sha256(),input.objectVersion(),input.url()+"&signature=new");
        assertThat(client.inputManifestHash(List.of(refreshed))).isEqualTo(client.inputManifestHash(List.of(input)));
        var foreign=new NativeS3NodeClient.Input(input.path(),input.size(),input.sha256(),input.objectVersion(),input.url().replace("/task/","/other-task/"));
        assertThat(client.inputManifestHash(List.of(foreign))).isNotEqualTo(client.inputManifestHash(List.of(input)));
        var unicode=new NativeS3NodeClient.Input("input/交易.csv",4,"a".repeat(64),"v1","https://silo.example/bucket/交易.csv?versionId=v1");
        assertThat(client.inputManifestHash(List.of(unicode))).isEqualTo("f71a83c51f4c7bdeebf4e5a41e6b4378085c8eaff204d230d04455eb39226bd1");
    }
    @Test void javaClientCarriesSignedAuthorityAndIdempotencyWithoutInterpretingBusinessSuccess()throws Exception{
        var observed=new AtomicReference<Map<String,String>>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            observed.set(Map.of("path",exchange.getRequestURI().getPath(),"authorization",exchange.getRequestHeaders().getFirst("Authorization"),
                "idempotency",Objects.toString(exchange.getRequestHeaders().getFirst("Idempotency-Key"),""),"body",new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] bytes="{\"acceptanceStatus\":\"PENDING_RUNTIME_VALIDATION\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try{
            var client=new NativeS3NodeClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),json);
            String token=client.signGrant(new LinkedHashMap<>(Map.of("sessionId","task-session","version",2)),"k".repeat(64));
            var result=client.create(token,List.of(),"immutable-create");
            assertThat(result.path("acceptanceStatus").asText()).isEqualTo("PENDING_RUNTIME_VALIDATION");
            assertThat(observed.get()).containsEntry("authorization","Bearer "+token).containsEntry("idempotency","immutable-create").containsEntry("body","{\"inputs\":[]}");
            client.message(token,"task-session","Choose your own project structure","message-1");
            assertThat(observed.get()).containsEntry("path","/v1/sessions/task-session/messages").containsEntry("idempotency","message-1");
            assertThatThrownBy(()->client.status(token,"../other")).isInstanceOf(IllegalArgumentException.class);
        }finally{server.stop(0);}
    }
    @Test void staleFenceFailureDoesNotExposeSignedStorageUrls()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{byte[] body="{\"message\":\"https://silo/secret?signature=private\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(409,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try{
            var client=new NativeS3NodeClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),json);
            assertThatThrownBy(()->client.status("signed.grant","session")).isInstanceOfSatisfying(NativeS3NodeClient.NodeException.class,
                e->{assertThat(e.status()).isEqualTo(409);assertThat(e.getMessage()).doesNotContain("signature","secret");});
        }finally{server.stop(0);}
    }
}
