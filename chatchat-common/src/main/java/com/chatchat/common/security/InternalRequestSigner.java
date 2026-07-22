package com.chatchat.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** HMAC authentication for internal HTTP calls; TLS remains responsible for payload confidentiality. */
public final class InternalRequestSigner {
    public static final String USER_HEADER = "X-Chatchat-User";
    public static final String TIMESTAMP_HEADER = "X-Chatchat-Timestamp";
    public static final String NONCE_HEADER = "X-Chatchat-Nonce";
    public static final String SIGNATURE_HEADER = "X-Chatchat-Signature";

    private InternalRequestSigner() { }

    public static String sign(String secret, String method, String path, String timestamp, String nonce) {
        try {
            String canonical = method.toUpperCase() + "\n" + path + "\n" + timestamp + "\n" + nonce;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("Cannot sign internal request", ex); }
    }

    public static boolean matches(String actual, String expected) {
        if (actual == null || expected == null) return false;
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII));
    }
}
