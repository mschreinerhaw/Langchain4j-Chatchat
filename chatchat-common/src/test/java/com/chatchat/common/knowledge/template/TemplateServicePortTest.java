package com.chatchat.common.knowledge.template;

import com.chatchat.common.bridge.AbstractRuntimeBridge;
import com.chatchat.common.bridge.BridgeContract;
import com.chatchat.common.bridge.BridgeRequest;
import com.chatchat.common.bridge.BridgeResponse;
import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateServicePortTest {

    @Test
    void invokesThroughDomainPayloadsAndKernelDataRanges() {
        TestAdapter adapter = new TestAdapter();
        Map<String, Object> extensions = new java.util.LinkedHashMap<>();
        extensions.put("limit", 10);
        extensions.put("optionalCursor", null);
        TemplateServiceCall call = TemplateServiceCall.search("customer profile", Map.of("env", "DEV"),
            Map.of("tenant", "tenant-a"), extensions);

        BridgeResponse<TemplateServiceResult> response = adapter.invoke(
            call, new KernelDataScope("tenant-a", "user-a", "request-a", null, "run-a", "DEV", Map.of()));

        assertThat(response.successful()).isTrue();
        assertThat(response.data().schemaVersion()).isEqualTo(TemplateServiceResult.SCHEMA_VERSION);
        assertThat(response.data().operation()).isEqualTo(TemplateServiceOperation.SEARCH);
        assertThat(adapter.observed.requestedReadData())
            .containsExactlyInAnyOrder(KernelDataDomain.CONTROL, KernelDataDomain.TOOL_ARGUMENTS);
        assertThat(adapter.observed.requestedWriteData())
            .containsExactlyInAnyOrder(KernelDataDomain.TOOL_RESULTS, KernelDataDomain.EVIDENCE,
                KernelDataDomain.EVENTS);
    }

    @Test
    void definesGovernedExecutionWithoutEndpointOrHttpFields() {
        TemplateServiceCall call = TemplateServiceCall.execute("customer_profile_v1",
            Map.of("customerId", "C-1"), Map.of("tenantId", "tenant-a"), "task-1",
            System.currentTimeMillis() + 10_000);

        assertThat(call.operation()).isEqualTo(TemplateServiceOperation.EXECUTE);
        assertThat(call.templateId()).isEqualTo("customer_profile_v1");
        assertThat(call.parameters()).containsOnlyKeys("customerId");
        assertThat(TemplateServiceCall.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("caller", "targetService", "url", "method", "headers", "body");
        assertThatThrownBy(() -> new TemplateServiceCall("template_service_call.v2", call.operation(),
            null, call.templateId(), Map.of(), call.parameters(), Map.of(), Map.of(), null, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemplateServiceCall.search("query", Map.of(), Map.of(),
            Map.of("url", "https://unapproved.example")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Raw transport definition is forbidden");
    }

    private static final class TestAdapter
        extends AbstractRuntimeBridge<TemplateServiceCall, TemplateServiceResult>
        implements TemplateServicePort {
        private final BridgeContract contract = new BridgeContract("test-template-service",
            "test_template_service.v1", KernelProtocolCatalog.TEMPLATE_SERVICE,
            Set.of(TemplateServiceOperation.SEARCH.operationCode()), KernelProtocolCatalog.SERVICE_BOUNDARY);
        private BridgeRequest<TemplateServiceCall> observed;

        @Override public BridgeContract bridgeContract() { return contract; }

        @Override protected TemplateServiceResult exchangePayload(BridgeRequest<TemplateServiceCall> request) {
            observed = request;
            return new TemplateServiceResult(TemplateServiceResult.SCHEMA_VERSION, request.requestId(),
                request.payload().operation(), TemplateServiceResultStatus.SUCCESS,
                Map.of("templates", List.of()), List.of(), false, request.metadata(),
                System.currentTimeMillis());
        }
    }
}
