package com.chatchat.knowledgebase.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchDocument {

    private String docId;
    private String title;
    private String content;
    private String source;
    private String date;
    @Builder.Default
    private List<String> tags = new ArrayList<>();
    @Builder.Default
    private List<String> companies = new ArrayList<>();
    @Builder.Default
    private List<String> industries = new ArrayList<>();
    @Builder.Default
    private List<String> keywords = new ArrayList<>();
    private String fileName;
    private String filePath;
    private String documentType;
    private Long fileSize;
    private Long uploadedAt;
    private Long updatedAt;
}
