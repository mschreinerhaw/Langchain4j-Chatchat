package com.chatchat.chat.interaction.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Database-owned mapping from request intent to a governed tool capability. */
@Getter
@Setter
@Entity
@Table(name = "agent_tool_intent_binding")
public class AgentToolIntentBinding {

    @Id
    @Column(name = "input_key", length = 128, nullable = false)
    private String inputKey;

    @Column(name = "requested_tool_name", length = 128, nullable = false)
    private String requestedToolName;

    @Column(name = "remote_tool_name", length = 128, nullable = false)
    private String remoteToolName;

    @Column(name = "local_tool_name_suffix", length = 128, nullable = false)
    private String localToolNameSuffix;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;
}
