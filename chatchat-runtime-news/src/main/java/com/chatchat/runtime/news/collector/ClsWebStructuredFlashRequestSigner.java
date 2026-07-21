package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsSource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Implements the deterministic signature used by CLS web JSON endpoints. */
@Component
public class ClsWebStructuredFlashRequestSigner implements StructuredFlashRequestSigner {
    @Override
    public String name() {
        return "CLS_WEB";
    }

    @Override
    public void sign(Map<String, String> query, NewsSource source) {
        query.put("app", "CailianpressWeb");
        query.put("os", "web");
        query.put("sv", stringConfig(source, "apiVersion", "8.7.9"));
        try {
            String canonical = new TreeMap<>(query).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("&"));
            byte[] sha1 = MessageDigest.getInstance("SHA-1")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] md5 = MessageDigest.getInstance("MD5")
                .digest(HexFormat.of().formatHex(sha1).getBytes(StandardCharsets.UTF_8));
            query.put("sign", HexFormat.of().formatHex(md5));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign CLS web request", ex);
        }
    }

    private String stringConfig(NewsSource source, String key, String fallback) {
        Object value = source.configuration().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
