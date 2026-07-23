package com.chatchat.mcpserver.license;

import com.chatchat.license.LicenseException;
import com.chatchat.license.LicenseManager;
import com.chatchat.license.LicensePayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpLicenseServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void expiredLicenseKeepsServiceInspectableButDeniesNewToolCalls() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String privateKey = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String publicKey = pem("PUBLIC KEY", pair.getPublic().getEncoded());
        Path licenseFile = tempDir.resolve("LIC-EXPIRED.dat");
        LicenseManager issuer = new LicenseManager(mapper, licenseFile, tempDir.resolve("issuer-server.id"), publicKey);
        LicensePayload expired = new LicensePayload("LIC-EXPIRED", "测试客户", "TEST", "LiveMCP",
            "enterprise", List.of("mcp"), 100, "*", LocalDate.now().minusDays(1),
            Map.of("sql_query", true), LocalDate.now().minusYears(1));
        Files.write(licenseFile, issuer.issue(expired, privateKey, "test-key"));
        LicenseProperties properties = new LicenseProperties();
        properties.setLicenseFile(tempDir.resolve("license.dat").toString());
        properties.setServerIdFile(tempDir.resolve("runtime-server.id").toString());
        properties.setPublicKey(publicKey);
        McpLicenseService service = new McpLicenseService(properties, mapper);

        assertThat(service.status().status()).isEqualTo("EXPIRED");
        assertThat(service.toolDenialReason("database_query")).contains("License 已过期");
        assertThatThrownBy(service::requireRuntimeLicense).isInstanceOf(LicenseException.class);
    }

    @Test
    void licenseEnforcementCannotBeDisabled() {
        LicenseProperties properties = new LicenseProperties();
        properties.setLicenseFile(tempDir.resolve("missing.dat").toString());
        properties.setServerIdFile(tempDir.resolve("server.id").toString());
        McpLicenseService service = new McpLicenseService(properties,
            new ObjectMapper().registerModule(new JavaTimeModule()));

        assertThat(service.enforcementEnabled()).isTrue();
        assertThat(service.toolDenialReason("database_query")).isNotBlank();
    }

    private static String pem(String type, byte[] content) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(content)
            + "\n-----END " + type + "-----";
    }
}
