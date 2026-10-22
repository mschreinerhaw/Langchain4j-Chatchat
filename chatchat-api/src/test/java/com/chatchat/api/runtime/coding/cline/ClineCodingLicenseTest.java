package com.chatchat.api.runtime.coding.cline;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import com.chatchat.common.runtime.coding.CodingContractException;
import com.chatchat.common.runtime.coding.CodingErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClineCodingLicenseTest {
    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "NOT_INSTALLED", "INVALID_FILE", "UNAVAILABLE"})
    void rejectsBeforeTaskAcceptanceOrWorkerCalls(String status) {
        var tasks = mock(DurableCodingTaskService.class);
        var license = mock(McpLicenseEntitlementPort.class);
        when(license.codingDispatchEntitlement()).thenReturn(
            new McpLicenseEntitlementPort.CodingDispatchEntitlement(false, status));
        var adapter = adapter(tasks);
        adapter.setLicenseEntitlements(license);

        assertThatThrownBy(() -> adapter.submit(null))
            .isInstanceOfSatisfying(CodingContractException.class,
                failure -> assertThat(failure.error().code()).isEqualTo(CodingErrorCode.AUTHORIZATION_DENIED))
            .hasMessageContaining("任务委派已暂停").hasMessageContaining("重试");
        verifyNoInteractions(tasks);
    }

    @Test
    void unavailableOrMissingLicenseProviderCannotBypassCheck() {
        var tasks = mock(DurableCodingTaskService.class);
        var adapter = adapter(tasks);
        assertThatThrownBy(() -> adapter.submit(null)).hasMessageContaining("无法核验产品授权");
        var license = mock(McpLicenseEntitlementPort.class);
        adapter.setLicenseEntitlements(license);
        assertThatThrownBy(() -> adapter.submit(null)).hasMessageContaining("无法核验产品授权");
        when(license.codingDispatchEntitlement()).thenThrow(new IllegalStateException("private endpoint"));
        assertThatThrownBy(() -> adapter.submit(null)).hasMessageContaining("检查授权服务后重试")
            .hasMessageNotContaining("private endpoint");
        verifyNoInteractions(tasks);
    }

    private ClineCodingAdapter adapter(DurableCodingTaskService tasks) {
        return new ClineCodingAdapter(tasks, null, null, null, null, null,
            URI.create("http://127.0.0.1:1"), "0123456789abcdef0123456789abcdef", new ObjectMapper());
    }
}
