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
@Table(name = "semantic_lexicon_entry")
public class SemanticLexiconEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 120, nullable = false)
    private String term;

    @Column(length = 120, nullable = false)
    private String normalizedTerm;

    @Column(length = 20)
    private String language;

    @Column(length = 120)
    private String mappedTerm;

    @Column(columnDefinition = "TEXT")
    private String aliases;

    @Column(length = 50)
    private String category;

    @Column(length = 50)
    private String domain;

    private Integer weight = 1;

    private Integer priority = 0;

    private Boolean builtin = false;

    private Boolean enabled = true;

    private Integer version = 1;

    private Long createdAt;

    private Long updatedAt;
}
