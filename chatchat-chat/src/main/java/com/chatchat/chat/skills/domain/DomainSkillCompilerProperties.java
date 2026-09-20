package com.chatchat.chat.skills.domain;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Model and attention-budget settings for domain-skill compilation and planning routing. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.domain-skills")
public class DomainSkillCompilerProperties {
    /** Blank keeps backward compatibility by using chatchat.models.defaultChatModel. */
    private String compilerModel = "";
    /** Blank reuses the current Agent model, then the platform default. */
    private String routerModel = "";
    private int maxActivatedSkills = 4;
    private int routerInputChars = 48 * 1024;
    private int planningTokenBudget = 6000;
}
