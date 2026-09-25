package com.chatchat.mcpserver.external;

import com.chatchat.mcpserver.templatepublication.catalog.TemplateQueryParentCatalog;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalSecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalMcpRegistryServiceTest {
    private final ExternalMcpServiceRepository repository = mock(ExternalMcpServiceRepository.class);
    private final FakeWorkflow workflow = new FakeWorkflow();
    private final InternalCredentialProperties credentials = mock(InternalCredentialProperties.class);
    private final ExternalMcpRegistryService registry = new ExternalMcpRegistryService(
        repository, new TemplateQueryParentCatalog(), List.of(workflow), new ObjectMapper(), credentials);

    @Test void registrationRequiresKnownParentAndStartsDisabled() {
        when(credentials.resolvedSecret()).thenReturn("test-secret-key");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        String parent = registry.parents().stream().filter(item -> item.assetType().equals("api_service"))
            .findFirst().orElseThrow().toolName();
        ExternalMcpService saved = registry.create(new ExternalMcpRegistryService.UpsertRequest(
            "Partner", "https://partner.example/mcp", "Bearer secret", parent, workflow.id()));
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getParentToolName()).isEqualTo(parent);
        assertThat(InternalSecretCipher.isEncrypted(saved.getAuthorization())).isTrue();
        assertThat(InternalSecretCipher.decryptIfNecessary(saved.getAuthorization(), "test-secret-key"))
            .isEqualTo("Bearer secret");

        assertThatThrownBy(() -> registry.create(new ExternalMcpRegistryService.UpsertRequest(
            "Bad", "https://partner.example/mcp", null, "unknown_parent", workflow.id())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void discoverySnapshotsTemplatesAndRequiresExplicitEnablement() {
        ExternalMcpService service = service();
        when(repository.findById("service-1")).thenReturn(Optional.of(service));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        assertThatThrownBy(() -> registry.setEnabled("service-1", true))
            .hasMessageContaining("Discover");
        registry.discover("service-1");
        assertThat(service.isEnabled()).isFalse();
        assertThat(registry.templates(service)).extracting(ExternalMcpRegistryService.ToolTemplate::name)
            .containsExactly("read_data", "write_data");
        assertThat(registry.templates(service)).extracting(ExternalMcpRegistryService.ToolTemplate::readOnly)
            .containsExactly(true, false);
        registry.setEnabled("service-1", true);
        assertThat(registry.invoke("service-1", "read_data", Map.of("query", "x", "userId", "injected")))
            .isNotNull();
        assertThat(workflow.lastArguments).containsExactlyInAnyOrderEntriesOf(Map.of("query", "x"));
        assertThatThrownBy(() -> registry.invoke("service-1", "write_data", Map.of()))
            .hasMessageContaining("read-only");
    }

    @Test void endpointChangeRevokesApprovalAndInvalidatesSnapshot() {
        ExternalMcpService service = service();
        service.setEnabled(true);
        service.setTemplatesJson("[]");
        service.setDiscoveredAt(java.time.Instant.now());
        when(repository.findById("service-1")).thenReturn(Optional.of(service));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        registry.update("service-1", new ExternalMcpRegistryService.UpsertRequest(
            "Partner", "https://another.example/mcp", "", service.getParentToolName(), workflow.id()));
        assertThat(service.isEnabled()).isFalse();
        assertThat(service.getTemplatesJson()).isNull();
    }

    @Test void rejectsNonHttpAndUnknownWorkflow() {
        String parent = registry.parents().get(0).toolName();
        assertThatThrownBy(() -> registry.create(new ExternalMcpRegistryService.UpsertRequest(
            "Bad", "file:///etc/passwd", null, parent, workflow.id())))
            .hasMessageContaining("HTTP(S)");
        assertThatThrownBy(() -> registry.create(new ExternalMcpRegistryService.UpsertRequest(
            "Bad", "https://example.org/mcp", null, parent, "unknown")))
            .hasMessageContaining("workflow");
        verify(repository, never()).save(any());
    }

    private ExternalMcpService service() {
        ExternalMcpService service = new ExternalMcpService();
        service.setId("service-1");
        service.setName("Partner");
        service.setEndpoint("https://partner.example/mcp");
        service.setParentToolName(registry.parents().get(0).toolName());
        service.setWorkflowId(workflow.id());
        return service;
    }

    private static final class FakeWorkflow implements ExternalMcpExecutionWorkflow {
        private Map<String, Object> lastArguments;
        @Override public String id() { return "mcp_streamable_http"; }
        @Override public List<McpSchema.Tool> discover(ExternalMcpService service) {
            Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));
            return List.of(
                McpSchema.Tool.builder().name("read_data").title("Read")
                    .inputSchema(schema).annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).build()).build(),
                McpSchema.Tool.builder().name("write_data").title("Write")
                    .inputSchema(schema).build());
        }
        @Override public McpSchema.CallToolResult invoke(ExternalMcpService service, String tool,
                                                           Map<String, Object> arguments) {
            lastArguments = arguments;
            return McpSchema.CallToolResult.builder().addTextContent("ok").isError(false).build();
        }
    }
}
