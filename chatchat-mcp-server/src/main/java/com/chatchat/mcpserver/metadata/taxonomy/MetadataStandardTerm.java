package com.chatchat.mcpserver.metadata.taxonomy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "mcp_metadata_standard_term")
public class MetadataStandardTerm {

    @Id
    @Column(length = 128)
    private String id;

    @Column(nullable = false, length = 256)
    private String chineseName;

    @Column(length = 256)
    private String englishName;

    @Column(length = 128)
    private String abbreviation;

    @Column(length = 2000)
    private String remark;

    @Column(nullable = false, length = 64)
    private String status = "active";

    @Column(length = 1000)
    private String source;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
