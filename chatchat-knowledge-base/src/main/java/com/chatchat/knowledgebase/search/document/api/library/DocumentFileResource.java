package com.chatchat.knowledgebase.search.document;

import org.springframework.core.io.Resource;

public record DocumentFileResource(
    Resource resource,
    String fileName,
    String documentType
) {
}
