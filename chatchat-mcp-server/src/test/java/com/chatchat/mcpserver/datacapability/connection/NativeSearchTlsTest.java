package com.chatchat.mcpserver.datacapability.connection;

import com.chatchat.mcpserver.ops.http.HttpEndpointConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import javax.net.ssl.*;
import static org.assertj.core.api.Assertions.*;

class NativeSearchTlsTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings = {"opensearch", "elasticsearch"})
    void trustsOnlyConfiguredCaAndStillVerifiesCertificateIdentity(String kind) throws Exception {
        Path store = directory.resolve("server.p12"), certificate = directory.resolve("ca.pem");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-dname", "CN=search.internal",
            "-ext", "SAN=dns:search.internal", "-validity", "2", "-keystore", store.toString(), "-storepass", "changeit");
        keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", store.toString(), "-storepass", "changeit",
            "-file", certificate.toString());
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "changeit".toCharArray()); }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "changeit".toCharArray());
        var ssl = SSLContext.getInstance("TLS"); ssl.init(managers.getKeyManagers(), null, null);
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/", exchange -> {
            byte[] body = "{\"cluster_name\":\"secure\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        var asset = new HttpEndpointConfig(); asset.setId("secure"); asset.setCategory(kind); asset.setTimeoutMs(5000);
        asset.setUrlTemplate("https://127.0.0.1:" + server.getAddress().getPort());
        var properties = new NativeSearchTlsProperties();
        var policy = new NativeSearchTlsProperties.Endpoint();
        policy.setUrl(asset.getUrlTemplate()); policy.setCaCertificateFile(certificate.toString());
        properties.getEndpoints().add(policy);
        try (var client = new NativeDatabaseClient(new ObjectMapper())) {
            assertThatThrownBy(() -> client.get(asset, "/", 5)).hasMessageContaining("PKIX");
            client.configureSearchTls(properties);
            assertThatThrownBy(() -> client.get(asset, "/", 5)).isInstanceOf(SSLException.class);
            policy.setServerName("search.internal");
            assertThat(client.get(asset, "/", 5).path("cluster_name").asText()).isEqualTo("secure");
            policy.setServerName("wrong.internal");
            assertThatThrownBy(() -> client.get(asset, "/", 5)).isInstanceOf(SSLException.class);
            policy.setUrl("https://unrelated.internal:9200");
            assertThatThrownBy(() -> client.get(asset, "/", 5)).hasMessageContaining("PKIX");
        } finally { server.stop(0); }
    }

    private void keytool(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(java.util.List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }
}
