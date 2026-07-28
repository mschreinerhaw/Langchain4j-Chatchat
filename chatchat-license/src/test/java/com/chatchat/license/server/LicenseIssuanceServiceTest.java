package com.chatchat.license.server;

import com.chatchat.license.LicenseCrypto;
import com.chatchat.license.LicenseDocument;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LicenseIssuanceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void normalizesCustomerMacAndSignsDownload() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Path privateKey = tempDir.resolve("private.pem");
        Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        LicenseCenterProperties properties = new LicenseCenterProperties();
        properties.setPrivateKeyPath(privateKey.toString());
        properties.setKeyId("internal-2026");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        LicenseIssuanceService service = new LicenseIssuanceService(properties, mapper);
        LicensePayload request = new LicensePayload("LIC-1", "客户", "C1", "LiveMCP", "enterprise",
            List.of("mcp"), 20, "aa:bb:cc:dd:ee:ff", LocalDate.now().plusYears(1),
            Map.of("sql_query", true), LocalDate.now());

        LicenseDocument issued = mapper.readValue(service.issue(request), LicenseDocument.class);

        assertEquals("MAC-AABBCCDDEEFF", issued.payload().serverId());
        assertEquals("internal-2026", issued.keyId());
        assertTrue(new LicenseCrypto(mapper).verify(issued, pem("PUBLIC KEY", pair.getPublic().getEncoded())));
    }

    @Test
    void createsPersistentKeyPairOnFirstIssue() throws Exception {
        Path privateKey = tempDir.resolve("keys/license-private.pem");
        Path publicKey = tempDir.resolve("keys/license-public.pem");
        LicenseCenterProperties properties = new LicenseCenterProperties();
        properties.setPrivateKeyPath(privateKey.toString());
        properties.setPublicKeyPath(publicKey.toString());
        properties.setKeyId("generated-2026");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        LicenseIssuanceService service = new LicenseIssuanceService(properties, mapper);
        LicensePayload request = new LicensePayload("LIC-AUTO", "Customer", "AUTO", "LiveMCP", "enterprise",
            List.of("mcp"), 10, "18:3d:2d:68:d9:b6", LocalDate.now().plusYears(1),
            Map.of("sql_query", true), LocalDate.now());

        LicenseDocument issued = mapper.readValue(service.issue(request), LicenseDocument.class);

        assertTrue(Files.exists(privateKey));
        assertTrue(Files.exists(publicKey));
        assertTrue(new LicenseCrypto(mapper).verify(issued, Files.readString(publicKey)));
    }

    @Test
    void rejectsMenuThatTargetMcpServiceDoesNotPublish() {
        LicenseCenterProperties properties = new LicenseCenterProperties();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        McpMenuCatalogClient catalog = mock(McpMenuCatalogClient.class);
        when(catalog.load()).thenReturn(List.of(
            new McpMenuCatalogClient.MenuModule("databaseMcp", "数据能力中心", "Coin")));
        LicenseIssuanceService service = new LicenseIssuanceService(properties, mapper, catalog);
        LicensePayload request = new LicensePayload("LIC-INVALID-MENU", "Customer", "C1", "LiveMCP", "enterprise",
            List.of("cacheSettings"), 10, "18:3d:2d:68:d9:b6", LocalDate.now().plusYears(1),
            Map.of(), LocalDate.now());

        var error = assertThrows(com.chatchat.license.LicenseException.class, () -> service.issue(request));

        assertTrue(error.getMessage().contains("未发布的菜单模块"));
    }

    private static String pem(String type, byte[] content) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(content)
            + "\n-----END " + type + "-----";
    }
}
