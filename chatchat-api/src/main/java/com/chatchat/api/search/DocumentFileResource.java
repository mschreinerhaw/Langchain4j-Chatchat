package com.chatchat.api.search;

import org.springframework.core.io.Resource;

public record DocumentFileResource(
    Resource resource,
    String fileName,
    String documentType
) {
}
