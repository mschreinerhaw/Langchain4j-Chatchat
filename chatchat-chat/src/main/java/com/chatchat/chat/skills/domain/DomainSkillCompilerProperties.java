package com.chatchat.chat.skills.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Model selection owned by the external domain-skill compiler. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.domain-skills")
public class DomainSkillCompilerProperties {
    /** Blank keeps backward compatibility by using chatchat.models.defaultChatModel. */
    private String compilerModel = "";
}
