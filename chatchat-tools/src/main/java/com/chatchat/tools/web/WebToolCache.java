package com.chatchat.tools.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

public interface WebToolCache {

    WebToolCache NOOP = new WebToolCache() {
    };

    default CacheLookup get(String namespace, String cacheKey) {
        return CacheLookup.miss();
    }

    default void put(String namespace, String cacheKey, Map<String, Object> data, long ttlSeconds) {
    }

    default String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }

    record CacheLookup(boolean hit, boolean expired, long ageSeconds, Map<String, Object> data) {

        public static CacheLookup hit(Map<String, Object> data, long ageSeconds) {
            return new CacheLookup(true, false, ageSeconds, data);
        }

        public static CacheLookup miss() {
            return new CacheLookup(false, false, 0L, Map.of());
        }

        public static CacheLookup expired(long ageSeconds) {
            return new CacheLookup(false, true, ageSeconds, Map.of());
        }
    }
}
