package com.chatchat.license;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class LicenseCrypto {

    private final ObjectMapper canonicalMapper;

    public LicenseCrypto(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public LicenseDocument sign(LicensePayload payload, String privateKeyPem, String keyId) {
        try {
            Signature signer = Signature.getInstance(LicenseDocument.ALGORITHM);
            signer.initSign(privateKey(privateKeyPem));
            signer.update(canonical(payload));
            return new LicenseDocument(
                LicenseDocument.FORMAT,
                LicenseDocument.ALGORITHM,
                keyId == null || keyId.isBlank() ? "default" : keyId.trim(),
                payload,
                Base64.getEncoder().encodeToString(signer.sign())
            );
        } catch (Exception ex) {
            throw new LicenseException("License 签名失败: " + ex.getMessage(), ex);
        }
    }

    public boolean verify(LicenseDocument document, String publicKeyPem) {
        if (document == null || document.payload() == null || document.signature() == null
            || !LicenseDocument.FORMAT.equals(document.format())
            || !LicenseDocument.ALGORITHM.equals(document.algorithm())) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(LicenseDocument.ALGORITHM);
            verifier.initVerify(publicKey(publicKeyPem));
            verifier.update(canonical(document.payload()));
            return verifier.verify(Base64.getDecoder().decode(document.signature()));
        } catch (Exception ex) {
            return false;
        }
    }

    private byte[] canonical(LicensePayload payload) throws Exception {
        return canonicalMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    }

    private PrivateKey privateKey(String pem) throws Exception {
        byte[] encoded = decodePem(pem, "PRIVATE KEY");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private PublicKey publicKey(String pem) throws Exception {
        byte[] encoded = decodePem(pem, "PUBLIC KEY");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    }

    private byte[] decodePem(String pem, String type) {
        if (pem == null || pem.isBlank()) {
            throw new LicenseException(type + " 未配置");
        }
        String content = pem
            .replace("-----BEGIN " + type + "-----", "")
            .replace("-----END " + type + "-----", "")
            .replaceAll("\\s", "");
        return Base64.getDecoder().decode(content);
    }
}
