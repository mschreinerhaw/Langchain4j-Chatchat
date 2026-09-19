package com.chatchat.license;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer maxAgents,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer maxSkills,
    String serverId,
    LocalDate expireTime,
    Map<String, Boolean> features,
    LocalDate issuedTime
) {
    public LicensePayload(String licenseNo, String customer, String customerCode, String product, String edition,
                          List<String> modules, Integer maxUsers, Integer maxAgents, String serverId,
                          LocalDate expireTime, Map<String, Boolean> features, LocalDate issuedTime) {
        this(licenseNo, customer, customerCode, product, edition, modules, maxUsers, maxAgents, null,
            serverId, expireTime, features, issuedTime);
    }

    public LicensePayload(String licenseNo, String customer, String customerCode, String product, String edition,
                          List<String> modules, Integer maxUsers, String serverId, LocalDate expireTime,
                          Map<String, Boolean> features, LocalDate issuedTime) {
        this(licenseNo, customer, customerCode, product, edition, modules, maxUsers, null, null, serverId,
            expireTime, features, issuedTime);
    }
}
