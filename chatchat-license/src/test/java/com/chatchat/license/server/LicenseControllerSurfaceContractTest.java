package com.chatchat.license.server;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseControllerSurfaceContractTest {
    @Test
    void licenseCenterControllerPublishesHttpOperations() {
        assertThat(LicenseCenterController.class.isAnnotationPresent(RestController.class)).isTrue();
        assertThat(Arrays.stream(LicenseCenterController.class.getDeclaredMethods())
            .flatMap(method -> Arrays.stream(method.getAnnotations()))
            .filter(annotation -> annotation.annotationType().getSimpleName().endsWith("Mapping")))
            .hasSizeGreaterThanOrEqualTo(2);
    }
}
