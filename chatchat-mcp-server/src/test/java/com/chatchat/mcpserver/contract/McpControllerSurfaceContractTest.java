package com.chatchat.mcpserver.contract;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpControllerSurfaceContractTest {
    private static final List<String> CONTROLLERS = List.of(
        "com.chatchat.mcpserver.admin.AdminAuthController",
        "com.chatchat.mcpserver.admin.AdminLoginAuditController",
        "com.chatchat.mcpserver.admin.AdminPageController",
        "com.chatchat.mcpserver.api.ApiServiceCategoryAdminController",
        "com.chatchat.mcpserver.api.ApiServiceController",
        "com.chatchat.mcpserver.audit.InvocationAuditController",
        "com.chatchat.mcpserver.authorization.McpAuthorizationAdminController",
        "com.chatchat.mcpserver.cache.DatabaseQueryCacheAdminController",
        "com.chatchat.mcpserver.category.BusinessCategoryAdminController",
        "com.chatchat.mcpserver.config.McpServerExceptionHandler",
        "com.chatchat.mcpserver.database.DataQueryCategoryAdminController",
        "com.chatchat.mcpserver.license.LicenseAdminController",
        "com.chatchat.mcpserver.livedata.LivedataApiController",
        "com.chatchat.mcpserver.market.MarketInternalController",
        "com.chatchat.mcpserver.market.SecurityMasterAdminController",
        "com.chatchat.mcpserver.mcp.McpServiceController",
        "com.chatchat.mcpserver.metadata.EnterpriseMetadataAdminController",
        "com.chatchat.mcpserver.metadata.EnterpriseMetadataTaxonomyAdminController",
        "com.chatchat.mcpserver.metadata.MetadataGovernancePolicyAdminController",
        "com.chatchat.mcpserver.notification.NotificationAdminController",
        "com.chatchat.mcpserver.routing.ExecutionTargetController",
        "com.chatchat.mcpserver.sql.SqlAdminController",
        "com.chatchat.mcpserver.sql.TradingCalendarConfigController",
        "com.chatchat.mcpserver.template.AgentRuntimeTemplateDslAdminController"
    );

    @Test
    void everyPublishedMcpAdminControllerOwnsAnHttpOrExceptionContract() throws Exception {
        int operations = 0;
        for (String name : CONTROLLERS) {
            Class<?> type = Class.forName(name);
            assertThat(type.isAnnotationPresent(RestController.class)
                || type.isAnnotationPresent(Controller.class)
                || type.getSimpleName().endsWith("ExceptionHandler")).as(name).isTrue();
            long count = List.of(type.getDeclaredMethods()).stream()
                .filter(this::isMappedOperation).count();
            assertThat(count).as(name + " mapped operations").isGreaterThan(0);
            operations += (int) count;
        }
        assertThat(operations).isGreaterThanOrEqualTo(100);
    }

    private boolean isMappedOperation(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            String name = annotation.annotationType().getSimpleName();
            if (name.endsWith("Mapping") || name.equals("ExceptionHandler")) return true;
        }
        return false;
    }
}
