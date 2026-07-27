package com.chatchat.mcpserver.metadata;

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
@Table(name = "mcp_metadata_standard_field")
public class MetadataStandardField {

    @Id
    @Column(length = 128)
    private String id;

    @Column(nullable = false, length = 256)
    private String chineseName;

    @Column(length = 512)
    private String fullPinyin;

    @Column(length = 256)
    private String englishName;

    @Column(length = 128)
    private String abbreviation;

    @Column(length = 128)
    private String dataType;

    @Column(length = 64)
    private String dataLength;

    @Column(length = 64)
    private String dataPrecision;

    @Column(length = 32)
    private String nullableFlag;

    @Column(length = 32)
    private String repeatableFlag;

    @Column(length = 1000)
    private String defaultValue;

    @Column(length = 2000)
    private String valueRange;

    @Column(length = 2000)
    private String standardDescription;

    @Column(length = 64)
    private String status;

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
