package com.chatchat.api.contract;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Owns the HTTP annotation surface; behavioral suites own the backing services and critical flows. */
class ApiControllerSurfaceContractTest {
    private static final List<String> CONTROLLERS = List.of(
        "com.chatchat.api.agent.task.AgentTaskScheduleController",
        "com.chatchat.api.controller.AgentWorkshopController",
        "com.chatchat.api.controller.DataQueryController",
        "com.chatchat.api.controller.HealthController",
        "com.chatchat.api.controller.ImageUnderstandingController",
        "com.chatchat.api.controller.McpProxyController",
        "com.chatchat.api.controller.McpServiceController",
        "com.chatchat.api.controller.RetrievalRuleController",
        "com.chatchat.api.controller.SidebarController",
        "com.chatchat.api.controller.UserWorkbenchController",
        "com.chatchat.api.enterprise.controller.EnterpriseAdminController",
        "com.chatchat.api.enterprise.controller.EnterpriseMcpAuthorizationSyncController"
    );

    @Test
    void everyPublishedApiControllerOwnsAtLeastOneHttpOperation() throws Exception {
        int operations = 0;
        for (String name : CONTROLLERS) {
            Class<?> type = Class.forName(name);
            assertThat(type.isAnnotationPresent(RestController.class)
                || type.isAnnotationPresent(Controller.class)).as(name).isTrue();
            long count = List.of(type.getDeclaredMethods()).stream()
                .filter(this::isHttpOperation).count();
            assertThat(count).as(name + " HTTP operations").isGreaterThan(0);
            operations += (int) count;
        }
        assertThat(operations).isGreaterThanOrEqualTo(100);
    }

    private boolean isHttpOperation(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation.annotationType().getSimpleName().endsWith("Mapping")) return true;
        }
        return false;
    }
}
