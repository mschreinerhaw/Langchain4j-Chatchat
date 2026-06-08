package com.chatchat.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sys_role")
public class SysRole extends EnterpriseAuditable {

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String roleCode;

    @Column(length = 128, nullable = false)
    private String roleName;

    @Column(length = 32, nullable = false)
    private String roleType = "business";

    @Column(length = 32, nullable = false)
    private String status = "enabled";

    @Column(length = 1000)
    private String description;
}
