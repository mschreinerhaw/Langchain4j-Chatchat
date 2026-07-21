package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsSource;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Implements the browser request header required by Securities Times JSON endpoints. */
@Component
public class StcnWebStructuredFlashRequestSigner implements StructuredFlashRequestSigner {
    private static final String PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCeIf6K3cnPaz3U35KRHppQlGLb"
            + "HWZMMcT/sPxNpxXKpCrZHzTJDydepNQjz29fSdwGIuP8XxWnGfZa+wiDLBMYKzV"
            + "9WUgpQQ2ZsxpV785x6BPzQOcIqFNGCb8WzSPaeFeFk9yG7SWsZoMlGTHBABHC7b"
            + "l0ZUGr+Vba2BnoV6HDpQIDAQAB";

    @Override
    public String name() {
        return "STCN_WEB";
    }

    @Override
    public void sign(Map<String, String> query, NewsSource source) {
        // STCN signs a timestamp header; its query parameters remain unchanged.
    }

    @Override
    public void signHeaders(Map<String, String> headers, NewsSource source) {
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            String payload = System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextDouble();
            headers.put("STCN-TIMESTAMP", Base64.getEncoder().encodeToString(
                cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign Securities Times web request", ex);
        }
    }
}
