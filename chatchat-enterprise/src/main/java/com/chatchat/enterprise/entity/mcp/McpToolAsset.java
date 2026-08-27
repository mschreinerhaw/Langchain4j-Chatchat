package com.chatchat.enterprise.entity.mcp;

import com.chatchat.enterprise.entity.common.EnterpriseAuditable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "mcp_tool")
public class McpToolAsset extends EnterpriseAuditable {

    @Column(length = 128, nullable = false, unique = true)
    private String localToolName;

    @Column(length = 64, nullable = false)
    private String serviceId;

    @Column(length = 128)
    private String serviceName;

    @Column(length = 128, nullable = false)
    private String remoteToolName;

    @Column(length = 2000)
    private String description;

    @Column(length = 32, nullable = false)
    private String resourceType = "tool";

    @Lob
    @Column(name = "input_schema_json", columnDefinition = "LONGTEXT")
    private String inputSchemaJson;

    @Lob
    @Column(name = "output_schema_json", columnDefinition = "LONGTEXT")
    private String outputSchemaJson;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 32, nullable = false)
    private String status = "online";
}
