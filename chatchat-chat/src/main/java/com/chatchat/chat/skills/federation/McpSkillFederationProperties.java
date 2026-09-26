package com.chatchat.chat.skills.federation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.skills.federation")
public class McpSkillFederationProperties {
    private String credentialKey = "";
    private int maxSkillsPerSource = 1000;
    private int pageSize = 100;
    private int maxSkillBytes = 1024 * 1024;
}
