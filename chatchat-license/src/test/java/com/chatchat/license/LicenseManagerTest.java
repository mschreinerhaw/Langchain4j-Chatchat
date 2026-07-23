package com.chatchat.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseManagerTest {

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;
    private LicenseManager manager;
    private String privateKey;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKey = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String publicKey = pem("PUBLIC KEY", pair.getPublic().getEncoded());
        manager = new LicenseManager(objectMapper, tempDir.resolve("license.dat"),
            tempDir.resolve("server.id"), publicKey);
    }

    @Test
    void issuesInstallsAndChecksSignedLicense() {
        byte[] content = manager.issue(payload(manager.serverId(), LocalDate.now().plusYears(1)), privateKey, "test-key");

        LicenseStatus status = manager.install(content);

        assertTrue(status.valid());
        assertEquals("VALID", status.status());
        assertTrue(manager.hasModule("MCP"));
        assertTrue(manager.hasFeature("sql_query"));
        assertEquals(manager.serverId(), Files.exists(tempDir.resolve("server.id"))
            ? read(tempDir.resolve("server.id")) : manager.serverId());
    }

    @Test
    void rejectsTamperedLicense() throws Exception {
        byte[] content = manager.issue(payload(manager.serverId(), LocalDate.now().plusYears(1)), privateKey, "test-key");
        LicenseDocument document = objectMapper.readValue(content, LicenseDocument.class);
        LicensePayload changed = new LicensePayload(document.payload().licenseNo(), "篡改客户",
            document.payload().customerCode(), document.payload().product(), document.payload().edition(),
            document.payload().modules(), document.payload().maxUsers(), document.payload().serverId(),
            document.payload().expireTime(), document.payload().features(), document.payload().issuedTime());
        byte[] tampered = objectMapper.writeValueAsBytes(new LicenseDocument(document.format(), document.algorithm(),
            document.keyId(), changed, document.signature()));

        assertThrows(LicenseException.class, () -> manager.install(tampered));
        assertFalse(Files.exists(tempDir.resolve("license.dat")));
    }

    @Test
    void invalidReplacementDoesNotOverwriteInstalledLicense() throws Exception {
        byte[] valid = manager.issue(payload(manager.serverId(), LocalDate.now().plusYears(1)), privateKey, "test-key");
        manager.install(valid);
        byte[] before = Files.readAllBytes(tempDir.resolve("license.dat"));
        byte[] otherServer = manager.issue(payload("SERVER-OTHER", LocalDate.now().plusYears(1)), privateKey, "test-key");

        assertThrows(LicenseException.class, () -> manager.install(otherServer));
        assertArrayEquals(before, Files.readAllBytes(tempDir.resolve("license.dat")));
        assertTrue(manager.status().valid());
    }

    @Test
    void rejectsExpiredLicense() {
        byte[] expired = manager.issue(payload(manager.serverId(), LocalDate.now().minusDays(1)), privateKey, "test-key");

        LicenseException exception = assertThrows(LicenseException.class, () -> manager.install(expired));
        assertTrue(exception.getMessage().contains("过期"));
    }

    private LicensePayload payload(String serverId, LocalDate expiry) {
        return new LicensePayload("LIC-TEST", "测试客户", "TEST", "LiveMCP", "enterprise",
            List.of("mcp", "news"), 100, serverId, expiry,
            Map.of("sql_query", true, "news_collect", true), LocalDate.now().minusDays(1));
    }

    private static String pem(String type, byte[] content) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(content)
            + "\n-----END " + type + "-----";
    }

    private static String read(Path path) {
        try {
            return Files.readString(path).trim();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
