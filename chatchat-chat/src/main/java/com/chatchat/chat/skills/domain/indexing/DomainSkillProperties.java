package com.chatchat.chat.skills.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration owned by the Agent domain-skill runtime. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.skills.domain")
public class DomainSkillProperties {
    private String indexName = "domain_skill_index";
}
