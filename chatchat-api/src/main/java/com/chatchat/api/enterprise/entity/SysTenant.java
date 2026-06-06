package com.chatchat.api.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sys_tenant")
public class SysTenant extends EnterpriseAuditable {

    @Column(length = 64, nullable = false, unique = true)
    private String tenantCode;

    @Column(length = 128, nullable = false)
    private String tenantName;

    @Column(length = 32, nullable = false)
    private String status = "enabled";

    @Column(length = 64)
    private String contactName;

    @Column(length = 64)
    private String contactPhone;

    @Column(length = 1000)
    private String description;
}
