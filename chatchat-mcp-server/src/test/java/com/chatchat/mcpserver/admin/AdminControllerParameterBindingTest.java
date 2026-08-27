package com.chatchat.mcpserver.admin;

import com.chatchat.mcpserver.api.category.ApiServiceCategoryAdminController;
import com.chatchat.mcpserver.category.BusinessCategoryAdminController;
import com.chatchat.mcpserver.database.category.DataQueryCategoryAdminController;
import com.chatchat.mcpserver.metadata.taxonomy.EnterpriseMetadataTaxonomyAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminControllerParameterBindingTest {

    @Test
    void routeParametersDeclareStableNamesWithoutCompilerMetadata() {
        List.of(
            ApiServiceCategoryAdminController.class,
            BusinessCategoryAdminController.class,
            DataQueryCategoryAdminController.class,
            EnterpriseMetadataTaxonomyAdminController.class
        ).stream()
            .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
            .flatMap(method -> Arrays.stream(method.getParameters()))
            .forEach(this::assertExplicitBindingName);
    }

    private void assertExplicitBindingName(Parameter parameter) {
        PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
        if (pathVariable != null) {
            assertThat(firstText(pathVariable.name(), pathVariable.value()))
                .as("@PathVariable on %s", parameter)
                .isNotBlank();
        }
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
        if (requestParam != null) {
            assertThat(firstText(requestParam.name(), requestParam.value()))
                .as("@RequestParam on %s", parameter)
                .isNotBlank();
        }
    }

    private String firstText(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
