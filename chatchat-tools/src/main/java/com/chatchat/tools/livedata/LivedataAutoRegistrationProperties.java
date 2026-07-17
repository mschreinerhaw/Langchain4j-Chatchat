package com.chatchat.tools.livedata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "chatchat.tools.livedata")
public class LivedataAutoRegistrationProperties {

    private boolean enabled = false;

    private String jdbcUrl;

    private String username;

    private String password;

    private String driverClass;

    private String tableName = "ld_dataservice_api";

    private String serviceBaseUrl;

    private String servicePathTemplate = "/service/{serviceName}/call";

    private boolean loginEnabled = true;

    private String loginPath = "/login";

    private String loginId;

    private String loginPwd;

    private int loginTimeoutMs = 10000;

    private int sessionTtlSeconds = 1800;

    private String amsToken;

    private String defaultNamespace = "livedata";

    private String toolNamePrefix = "livedata_";

    private int publishedState = 0;

    private int maxApis = 1000;

    private int timeoutMs = 20000;

    private boolean cacheEnabled = false;

    private int cacheTtlSeconds = 300;

    private boolean overwriteExisting = true;

    private boolean includeUnpublishedAsDisabled = false;

    private boolean exposeAmsTokenParameter = false;
}
