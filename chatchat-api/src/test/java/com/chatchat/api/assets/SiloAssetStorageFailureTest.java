package com.chatchat.api.assets;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

/** Real Minio SDK XML transport; no production credentials or object writes. */
class SiloAssetStorageFailureTest {
    @Test void actualCapacityProtectionResponseBecomesSafeActionable507() throws Exception {
        withS3Error(507,"XMinioStorageFull","Storage backend has reached its minimum free drive threshold. Please delete a few objects to proceed.",store->{
            assertThatThrownBy(()->store.put("private-source-key",new byte[]{1},"application/octet-stream","a".repeat(64)))
                .isInstanceOfSatisfying(ResponseStatusException.class,error->{
                    assertThat(error.getStatusCode().value()).isEqualTo(507);
                    assertThat(error.getReason()).contains("ARTIFACT_STORAGE_FULL","核对当前任务状态","不代表业务数据不存在")
                        .doesNotContain("private-source-key","unit-test-secret","127.0.0.1");
                });
        });
    }
    @Test void accessDenialIsNotMisclassifiedAsMissingDataOrCapacity() throws Exception {
        withS3Error(403,"AccessDenied","Access Denied",store->{
            assertThatThrownBy(()->store.put("input",new byte[]{1},"application/octet-stream","a".repeat(64)))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage("Asset object publication failed");
        });
    }
    private void withS3Error(int status,String code,String message,java.util.function.Consumer<SiloAssetBlobStore> action) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] body=("<Error><Code>"+code+"</Code><Message>"+message+"</Message><Resource>/private</Resource><RequestId>unit-test</RequestId></Error>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/xml");exchange.sendResponseHeaders(status,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.start();
        try {
            var settings=new AssetStorageProperties();settings.setEndpoint("http://127.0.0.1:"+server.getAddress().getPort());
            settings.setRegion("us-east-1");settings.setBucket("runtime-assets");settings.setAccessKey("unit-test");settings.setSecretKey("unit-test-secret");
            action.accept(new SiloAssetBlobStore(settings));
        } finally {server.stop(0);}
    }
}
