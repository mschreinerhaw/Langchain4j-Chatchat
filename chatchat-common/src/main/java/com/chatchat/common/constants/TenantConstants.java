package com.chatchat.common.constants;

/**
 * Stable business identifiers used by the multi-tenant platform.
 */
public final class TenantConstants {

    public static final long PLATFORM_TENANT_NO = 100000L;
    public static final long FIRST_BUSINESS_TENANT_NO = PLATFORM_TENANT_NO + 1L;

    private TenantConstants() {
    }
}
