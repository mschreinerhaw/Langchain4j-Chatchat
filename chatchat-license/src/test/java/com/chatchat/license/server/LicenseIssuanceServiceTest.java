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

    private static String pem(String type, byte[] content) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(content)
            + "\n-----END " + type + "-----";
    }
}
