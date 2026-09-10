package com.chatchat.knowledgebase.search.document;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

public record DocumentSearchFilters(
    String fileType,
    String chunkType,
    String tag,
    String company,
    String industry,
    @JsonAlias({"tags", "documentTags", "document_tags"})
    List<String> tags
) {
    public DocumentSearchFilters(String fileType,
                                 String chunkType,
                                 String tag,
                                 String company,
                                 String industry) {
        this(fileType, chunkType, tag, company, industry, List.of());
    }

    public DocumentSearchFilters {
        tags = tags == null ? List.of() : tags.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    public List<String> allTags() {
        if (tag == null || tag.isBlank()) {
            return tags;
        }
        if (tags.isEmpty()) {
            return List.of(tag.trim());
        }
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(tag.trim()), tags.stream())
            .distinct()
            .toList();
    }
}
