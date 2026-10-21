package com.chatchat.api.assets;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "chatchat.assets")
public class AssetStorageProperties {
    private boolean enabled;
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String accessKeyFile;
    private String secretKeyFile;
    private String bucket = "runtime-assets";
    private String region = "us-east-1";
    private long maxFileBytes = 64L * 1024 * 1024;
    private long userQuotaBytes = 2L * 1024 * 1024 * 1024;
}
