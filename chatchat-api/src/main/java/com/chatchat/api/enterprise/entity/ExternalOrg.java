package com.chatchat.api.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

@Getter
@Setter
@Entity
@Immutable
@Table(name = "lborganization")
public class ExternalOrg {

    @Id
    @Column(name = "ID", nullable = false)
    private Long id;

    @Column(name = "FID")
    private Long fid;

    @Column(name = "Grade")
    private Long grade;

    @Column(name = "OrgCode", length = 30)
    private String orgCode;

    @Column(name = "Name", length = 200)
    private String name;

    @Column(name = "FDNCode", length = 300)
    private String fdnCode;

    @Column(name = "OrgOrder")
    private Long orgOrder;

    @Column(name = "STATUS")
    private Long status;
}
