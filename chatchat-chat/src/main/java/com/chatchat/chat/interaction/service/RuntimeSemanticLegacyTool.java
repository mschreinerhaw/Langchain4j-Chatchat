package com.chatchat.chat.interaction.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Tool identity captured when the name-rule compatibility boundary was introduced. */
@Getter
@Setter
@Entity
@Table(name = "agent_runtime_legacy_tool")
public class RuntimeSemanticLegacyTool {
    @Id
    @Column(name = "tool_name", length = 256, nullable = false)
    private String toolName;
}
