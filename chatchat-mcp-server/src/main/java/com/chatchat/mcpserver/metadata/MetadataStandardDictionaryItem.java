package com.chatchat.mcpserver.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "mcp_metadata_standard_dictionary_item", uniqueConstraints = {
    @UniqueConstraint(name = "uk_metadata_dictionary_code", columnNames = {"dictionary_id", "code"})
}, indexes = {
    @Index(name = "idx_metadata_dictionary_item_dictionary", columnList = "dictionary_id")
})
public class MetadataStandardDictionaryItem {

    @Id
    @Column(length = 255)
    private String id;

    @Column(name = "dictionary_id", nullable = false, length = 128)
    private String dictionaryId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dictionary_id", insertable = false, updatable = false,
        foreignKey = @ForeignKey(name = "fk_metadata_dictionary_item"))
    private MetadataStandardDictionary dictionary;

    @Column(nullable = false, length = 128)
    private String code;

    @Column(length = 2000)
    private String codeDescription;

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
