package com.chatchat.api.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sys_org")
public class SysOrg extends EnterpriseAuditable {

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64)
    private String parentId;

    @Column(length = 64, nullable = false)
    private String orgCode;

    @Column(length = 128, nullable = false)
    private String orgName;

    @Column(nullable = false)
    private Integer sortOrder = 0;

    @Column(length = 32, nullable = false)
    private String status = "enabled";
}
