package com.chatchat.license;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record LicensePayload(
    String licenseNo,
    String customer,
    String customerCode,
    String product,
    String edition,
    List<String> modules,
    Integer maxUsers,
    String serverId,
    LocalDate expireTime,
    Map<String, Boolean> features,
    LocalDate issuedTime
) {
}
