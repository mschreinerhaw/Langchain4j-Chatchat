package com.chatchat.api.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "sys_user")
public class SysUser extends EnterpriseAuditable {

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64)
    private String orgId;

    @Column(length = 64, nullable = false, unique = true)
    private String username;

    @Column(length = 128, nullable = false)
    private String displayName;

    @Column(length = 255)
    private String passwordHash;

    @Column(length = 128)
    private String email;

    @Column(length = 64)
    private String phone;

    @Column(length = 32, nullable = false)
    private String status = "enabled";

    @Column
    private Instant lastLoginAt;
}
