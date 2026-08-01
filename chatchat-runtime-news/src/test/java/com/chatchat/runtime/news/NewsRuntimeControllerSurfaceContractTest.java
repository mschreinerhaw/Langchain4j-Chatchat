package com.chatchat.runtime.news;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class NewsRuntimeControllerSurfaceContractTest {
    @Test
    void newsRuntimeInternalControllerPublishesCompleteAdministrativeSurface() {
        assertThat(NewsRuntimeInternalController.class.isAnnotationPresent(RestController.class)).isTrue();
        assertThat(Arrays.stream(NewsRuntimeInternalController.class.getDeclaredMethods())
            .flatMap(method -> Arrays.stream(method.getAnnotations()))
            .filter(annotation -> annotation.annotationType().getSimpleName().endsWith("Mapping")))
            .hasSizeGreaterThanOrEqualTo(10);
    }
}
