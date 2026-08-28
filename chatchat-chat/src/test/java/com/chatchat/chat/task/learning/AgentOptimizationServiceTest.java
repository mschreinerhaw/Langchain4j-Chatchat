package com.chatchat.chat.task.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AgentOptimizationServiceTest {

    @Mock AgentOptimizationProposalRepository proposalRepository;
    @Mock AgentExperienceRepository experienceRepository;
    private final AtomicReference<AgentOptimizationProposalEntity> persisted = new AtomicReference<>();
    private AgentOptimizationService service;

    @BeforeEach
    void setUp() {
        lenient().when(proposalRepository.save(any())).thenAnswer(invocation -> {
            AgentOptimizationProposalEntity entity = invocation.getArgument(0);
            persisted.set(entity);
            return entity;
        });
        lenient().when(proposalRepository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
        service = new AgentOptimizationService(proposalRepository, experienceRepository,
            new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void requiresTenantOwnedEvidenceAndCompletesControlledLifecycle() {
        AgentExperienceEntity experience = new AgentExperienceEntity();
        experience.setExperienceId("experience-1");
        experience.setTenantId("tenant-1");
        when(experienceRepository.findById("experience-1")).thenReturn(Optional.of(experience));

        AgentOptimizationService.ProposalView draft = service.propose(
            new AgentOptimizationService.ProposeCommand("tenant-1", "agent-1", null,
                List.of("experience-1"), Map.of("systemPrompt", "safer prompt"),
                Map.of("reason", "low score"), "author-1"));
        assertThat(draft.status()).isEqualTo("DRAFT");

        service.validate("tenant-1", draft.proposalId(), Map.of("passed", true, "total", 12));
        service.approve("tenant-1", draft.proposalId(), "reviewer-1");
        service.startCanary("tenant-1", draft.proposalId(), 10);
        assertThat(service.completeCanary("tenant-1", draft.proposalId(),
            Map.of("passed", true, "sampleSize", 20)).status()).isEqualTo("READY_FOR_ROLLOUT");
        assertThat(service.markRolledOut("tenant-1", draft.proposalId(), "reviewer-1").status())
            .isEqualTo("ROLLED_OUT");
    }

    @Test
    void rejectsCrossTenantEvidenceAndUnsafePatchFields() {
        AgentExperienceEntity foreign = new AgentExperienceEntity();
        foreign.setExperienceId("foreign");
        foreign.setTenantId("tenant-2");
        when(experienceRepository.findById("foreign")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.propose(new AgentOptimizationService.ProposeCommand(
            "tenant-1", "agent-1", null, List.of("foreign"),
            Map.of("systemPrompt", "x"), Map.of(), "author")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("another tenant");

        AgentExperienceEntity owned = new AgentExperienceEntity();
        owned.setExperienceId("owned");
        owned.setTenantId("tenant-1");
        when(experienceRepository.findById("owned")).thenReturn(Optional.of(owned));
        assertThatThrownBy(() -> service.propose(new AgentOptimizationService.ProposeCommand(
            "tenant-1", "agent-1", null, List.of("owned"),
            Map.of("marketStatus", "PUBLISHED"), Map.of(), "author")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("forbidden fields");
    }
}
