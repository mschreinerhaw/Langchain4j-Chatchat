package com.chatchat.mcpserver.contract;

import com.chatchat.mcpserver.api.category.ApiServiceCategoryAdminController;
import com.chatchat.mcpserver.api.registry.ApiServiceController;

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
        "com.chatchat.mcpserver.api.category.ApiServiceCategoryAdminController",
        "com.chatchat.mcpserver.api.registry.ApiServiceController",
        "com.chatchat.mcpserver.audit.InvocationAuditController",
        "com.chatchat.mcpserver.authorization.McpAuthorizationAdminController",
        "com.chatchat.mcpserver.cache.query.DatabaseQueryCacheAdminController",
        "com.chatchat.mcpserver.category.BusinessCategoryAdminController",
        "com.chatchat.mcpserver.config.McpServerExceptionHandler",
        "com.chatchat.mcpserver.database.category.DataQueryCategoryAdminController",
        "com.chatchat.mcpserver.license.LicenseAdminController",
        "com.chatchat.mcpserver.livedata.LivedataApiController",
        "com.chatchat.mcpserver.market.MarketInternalController",
        "com.chatchat.mcpserver.market.SecurityMasterAdminController",
        "com.chatchat.mcpserver.mcp.McpServiceController",
        "com.chatchat.mcpserver.metadata.catalog.EnterpriseMetadataAdminController",
        "com.chatchat.mcpserver.metadata.taxonomy.EnterpriseMetadataTaxonomyAdminController",
        "com.chatchat.mcpserver.metadata.governance.MetadataGovernancePolicyAdminController",
        "com.chatchat.mcpserver.notification.NotificationAdminController",
        "com.chatchat.mcpserver.routing.target.ExecutionTargetController",
        "com.chatchat.mcpserver.sql.admin.SqlAdminController",
        "com.chatchat.mcpserver.sql.calendar.TradingCalendarConfigController",
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
