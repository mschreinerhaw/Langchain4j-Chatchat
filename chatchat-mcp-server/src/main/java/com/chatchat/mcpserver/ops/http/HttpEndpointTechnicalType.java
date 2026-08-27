package com.chatchat.mcpserver.ops.http;

import java.util.Locale;

/**
 * Technical implementation classification for an API gateway asset.
 */
public enum HttpEndpointTechnicalType {
    MICROSERVICE,
    HTTP;

    public static HttpEndpointTechnicalType from(String value) {
        if (value == null || value.isBlank()) {
            return HTTP;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("technicalType must be MICROSERVICE or HTTP");
        }
    }
}
