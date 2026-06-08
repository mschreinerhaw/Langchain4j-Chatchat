package com.chatchat.enterprise.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Getter
@Setter
@Entity
@Immutable
@Table(name = "tuser")
public class ExternalUser {

    @Id
    @Column(name = "ID", nullable = false)
    private Long id;

    @Column(name = "UserID", length = 50)
    private String userId;

    @Column(name = "Password", length = 64)
    private String password;

    @Column(name = "Name", length = 30)
    private String name;

    @Column(name = "OrgID")
    private Long orgId;

    @Column(name = "Status")
    private Long status;

    @Column(name = "LastLogin")
    private Instant lastLogin;

    @Column(name = "OA_TELPHONE", length = 50)
    private String oaTelephone;

    @Column(name = "HR_TELEPHONE", length = 30)
    private String hrTelephone;

    @Column(name = "OA_EMAIL", length = 100)
    private String oaEmail;

    @Column(name = "EML", length = 128)
    private String email;

    @Column(name = "OA_FNO", length = 100)
    private String oaFno;

    @Lob
    @Column(name = "Photo")
    private byte[] photo;
}
