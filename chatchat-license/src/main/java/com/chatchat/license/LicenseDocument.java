package com.chatchat.license;

public record LicenseDocument(
    String format,
    String algorithm,
    String keyId,
    LicensePayload payload,
    String signature
) {
    public static final String FORMAT = "LiveMCP-License-v1";
    public static final String ALGORITHM = "SHA256withRSA";
}
