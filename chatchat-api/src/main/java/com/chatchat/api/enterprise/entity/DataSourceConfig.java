package com.chatchat.api.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "data_source")
public class DataSourceConfig extends EnterpriseAuditable {

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(length = 32, nullable = false)
    private String type;

    @Column(length = 512, nullable = false)
    private String jdbcUrl;

    @Column(length = 128)
    private String username;

    @Column(length = 512)
    private String passwordCipher;

    @Column(length = 32, nullable = false)
    private String status = "enabled";

    @Column(length = 1000)
    private String remark;
}
