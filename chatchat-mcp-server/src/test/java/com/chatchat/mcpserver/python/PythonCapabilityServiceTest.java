package com.chatchat.mcpserver.python;

import com.chatchat.common.security.InternalCredentialProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PythonCapabilityServiceTest {
    @Mock private PythonEnvironmentRepository environments;
    @Mock private PythonTemplateAssetRepository templates;
    @Mock private PythonRuntimeExecutionRepository executions;
    @Mock private PythonDockerRuntime runtime;
    @Mock private InternalCredentialProperties credentials;
    @Mock private PythonMcpToolPublisher publisher;
    @Mock private PythonTemplateArgumentResolver argumentResolver;
    @Mock private PythonTemplateSearchService templateSearch;

    private PythonCapabilityService service;

    @BeforeEach
    void setUp() {
        service = new PythonCapabilityService(environments, templates, executions, runtime, credentials,
            publisher, new ObjectMapper(), argumentResolver, templateSearch);
    }

    @Test
    void disabledEnvironmentCanBeEditedAsNextDraftVersion() {
        PythonEnvironment environment = environment("DISABLED", 2);
        when(environments.findById("env-1")).thenReturn(Optional.of(environment));
        when(environments.save(any(PythonEnvironment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PythonEnvironment saved = service.saveEnvironment(request());

        assertEquals("DRAFT", saved.getStatus());
        assertEquals(3, saved.getVersionNumber());
        assertEquals("chatchat-python-runtime:3.11-v2", saved.getDockerImage());
    }

    @Test
    void publishedEnvironmentMustBeUnpublishedBeforeEditing() {
        when(environments.findById("env-1")).thenReturn(Optional.of(environment("PUBLISHED", 2)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.saveEnvironment(request()));

        assertEquals("请先取消发布环境再进行修改", error.getMessage());
    }

    private PythonEnvironment environment(String status, int version) {
        PythonEnvironment environment = new PythonEnvironment();
        environment.setId("env-1");
        environment.setStatus(status);
        environment.setVersionNumber(version);
        return environment;
    }

    private PythonCapabilityService.EnvironmentRequest request() {
        return new PythonCapabilityService.EnvironmentRequest("env-1", "数据科学环境", "二次编辑",
            "chatchat-python-runtime:3.11-v2", "3.11", "2", "4g", "2g", "512m",
            "10001:10001", "NONE", "", List.of("pandas==2.2.2"), 300);
    }
}
