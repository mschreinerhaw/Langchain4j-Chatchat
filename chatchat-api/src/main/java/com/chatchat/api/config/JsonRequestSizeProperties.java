package com.chatchat.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.http.json-request")
public class JsonRequestSizeProperties {
    private boolean enabled = true;
    private int maxBytes = 2_097_152;
    private int warningBytes = 524_288;

    public int safeMaxBytes() { return Math.max(65_536, Math.min(8_388_608, maxBytes)); }
    public int safeWarningBytes() { return Math.max(16_384, Math.min(safeMaxBytes(), warningBytes)); }
}
