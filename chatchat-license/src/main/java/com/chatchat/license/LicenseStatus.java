package com.chatchat.license;

public record LicenseStatus(
    boolean valid,
    String status,
    String message,
    String serverId,
    LicensePayload license
) {
    public static LicenseStatus invalid(String status, String message, String serverId, LicensePayload license) {
        return new LicenseStatus(false, status, message, serverId, license);
    }

    public static LicenseStatus valid(String serverId, LicensePayload license) {
        return new LicenseStatus(true, "VALID", "License 有效", serverId, license);
    }
}
