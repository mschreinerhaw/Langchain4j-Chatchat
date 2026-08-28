package com.chatchat.chat.runtime;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.event.NoopAgentRunEventPublisher;
import com.chatchat.agents.runtime.run.AgentRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseAgentRunStoreTest {

    @Mock AgentRuntimeRunRepository runRepository;
    @Mock AgentRuntimePlanRepository planRepository;
    @Mock AgentRuntimeCheckpointRepository checkpointRepository;
    private final AtomicReference<AgentRuntimeRunEntity> persisted = new AtomicReference<>();
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(runRepository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            AgentRuntimeRunEntity entity = invocation.getArgument(0);
            persisted.set(entity);
            return entity;
        });
    }

    @Test
    void persistsSerializableAggregateAndRehydratesAcrossStoreInstances() {
        DatabaseAgentRunStore first = store();
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("__executionId", "exec-1");
        attributes.put("__executionAttemptNumber", 2);
        attributes.put("__agentCancellation", (java.util.function.BooleanSupplier) () -> false);
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("attempt-2")
            .requestId("request-1")
            .tenantId("tenant-1")
            .attributes(attributes)
            .build();

        first.start(request);

        assertThat(persisted.get().getExecutionId()).isEqualTo("exec-1");
        assertThat(persisted.get().getAttemptNumber()).isEqualTo(2);
        assertThat(persisted.get().getRunJson()).doesNotContain("__agentCancellation");

        DatabaseAgentRunStore second = store();
        assertThat(second.find("attempt-2")).isPresent();
        assertThat(second.find("attempt-2").orElseThrow().status()).isEqualTo(AgentRunStatus.RUNNING);
    }

    private DatabaseAgentRunStore store() {
        return new DatabaseAgentRunStore(new NoopAgentRunEventPublisher(), new AgentRuntimeProperties(),
            runRepository, planRepository, checkpointRepository, objectMapper);
    }
}
