package com.chatchat.license.server;

import com.chatchat.license.LicenseCrypto;
import com.chatchat.license.LicenseDocument;
import com.chatchat.license.LicenseException;
import com.chatchat.license.LicensePayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseDeliveryPackageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void packagesLicensePublicKeyAndInstructionsAndVerifiesWithDeliveredKey() throws Exception {
        Fixture fixture = fixture();
        byte[] license = fixture.issuanceService.issue(payload());

        var delivery = new LicenseDeliveryPackageService(fixture.issuanceService, fixture.mapper)
            .create(license, "LIC/DELIVERY-1");
        Map<String, byte[]> files = unzip(delivery.content());

        assertEquals("RuiHeng-Nexus-LIC-DELIVERY-1-license-package.zip", delivery.fileName());
        assertEquals("application/zip", delivery.contentType());
        assertEquals(List.of("README.txt", "license-public.pem", "license.dat"), files.keySet().stream().sorted().toList());
        assertArrayEquals(license, files.get("license.dat"));
        assertEquals(Files.readString(fixture.publicKey), new String(files.get("license-public.pem"), StandardCharsets.UTF_8));
        LicenseDocument document = fixture.mapper.readValue(files.get("license.dat"), LicenseDocument.class);
        assertTrue(new LicenseCrypto(fixture.mapper).verify(document,
            new String(files.get("license-public.pem"), StandardCharsets.UTF_8)));
        String instructions = new String(files.get("README.txt"), StandardCharsets.UTF_8);
        assertTrue(instructions.contains("CHATCHAT_LICENSE_PUBLIC_KEY_PATH"));
        assertFalse(instructions.contains("私钥"));
    }

    @Test
    void rejectsDeliveryWhenPublicKeyDoesNotMatchSignedLicense() throws Exception {
        Fixture fixture = fixture();
        byte[] license = fixture.issuanceService.issue(payload());
        KeyPair anotherPair = keyPair();
        Files.writeString(fixture.publicKey, pem("PUBLIC KEY", anotherPair.getPublic().getEncoded()));

        LicenseException error = assertThrows(LicenseException.class,
            () -> new LicenseDeliveryPackageService(fixture.issuanceService, fixture.mapper).create(license, "LIC-1"));

        assertTrue(error.getMessage().contains("不匹配"));
    }

    private Fixture fixture() throws Exception {
        KeyPair pair = keyPair();
        Path privateKey = tempDir.resolve("license-private.pem");
        Path publicKey = tempDir.resolve("license-public.pem");
        Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        LicenseCenterProperties properties = new LicenseCenterProperties();
        properties.setPrivateKeyPath(privateKey.toString());
        properties.setPublicKeyPath(publicKey.toString());
        properties.setAutoGenerateKeys(false);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new Fixture(new LicenseIssuanceService(properties, mapper), mapper, publicKey);
    }

    private LicensePayload payload() {
        return new LicensePayload("LIC-DELIVERY-1", null, "C1", "LiveMCP", "enterprise",
            List.of("mcp"), 10, "18:3d:2d:68:d9:b6", LocalDate.now().plusYears(1),
            Map.of("agent_runtime", true), LocalDate.now());
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        return files;
    }

    private static String pem(String type, byte[] content) {
        return "-----BEGIN " + type + "-----\n"
            + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(content)
            + "\n-----END " + type + "-----";
    }

    private record Fixture(LicenseIssuanceService issuanceService, ObjectMapper mapper, Path publicKey) { }
}
