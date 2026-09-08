package com.chatchat.api.architecture;

import com.chatchat.api.controller.SearchController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSearchOwnershipBoundaryTest {

    @Test
    void apiDoesNotPublishMcpDocumentSearchEndpoints() {
        assertThat(Arrays.stream(SearchController.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(PostMapping.class))
            .flatMap(method -> Arrays.stream(method.getAnnotation(PostMapping.class).value()))
            .map(String::toLowerCase)
            .anyMatch(path -> path.contains("document-search")))
            .isFalse();
        assertThat(Arrays.stream(SearchController.class.getDeclaredFields())
            .map(field -> field.getType().getName())
            .anyMatch(type -> type.contains("DocumentSearchEvidenceService")))
            .isFalse();
    }
}
