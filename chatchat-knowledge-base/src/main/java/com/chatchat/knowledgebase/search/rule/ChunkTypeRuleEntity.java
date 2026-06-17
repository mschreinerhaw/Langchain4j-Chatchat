package com.chatchat.knowledgebase.search.rule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "chunk_type_rule")
public class ChunkTypeRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50, nullable = false)
    private String chunkType;

    @Column(columnDefinition = "TEXT")
    private String keywords;

    @Column(columnDefinition = "TEXT")
    private String pattern;

    private Integer weight = 1;

    private Integer priority = 0;

    private Boolean enabled = true;

    private Integer version = 1;

    private Long createdAt;

    private Long updatedAt;
}
