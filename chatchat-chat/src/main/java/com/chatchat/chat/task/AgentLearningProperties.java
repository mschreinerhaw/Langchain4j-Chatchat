package com.chatchat.chat.task;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.agent.learning")
public class AgentLearningProperties {

    private boolean enabled = true;
    private boolean modelAttributionEnabled = true;
    private int experienceLimit = 20;
    private int scenarioLimit = 8;
}
