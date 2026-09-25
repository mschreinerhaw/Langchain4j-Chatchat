package com.chatchat.enterprise.service;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import com.chatchat.common.runtime.capability.CapabilityId;
import com.chatchat.enterprise.entity.agent.AgentComputeDefinition;
import com.chatchat.enterprise.repository.agent.AgentComputeDefinitionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Durable enterprise implementation of the Agent Registry port. */
@Primary
@Service
public class JpaAgentRegistry implements AgentRegistryPort {
    private final AgentComputeDefinitionRepository repository;
    private final ObjectMapper mapper;

    public JpaAgentRegistry(AgentComputeDefinitionRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override public Optional<AgentDescriptor> find(String agentId) {
        if (agentId == null || agentId.isBlank()) return Optional.empty();
        return repository.findByAgentId(agentId.trim()).map(this::decode);
    }

    @Override public List<AgentDescriptor> findByCapability(CapabilityId capability) {
        return list().stream().filter(AgentDescriptor::enabled).filter(value -> value.provides(capability))
            .sorted(Comparator.comparingInt(AgentDescriptor::priority).reversed()
                .thenComparing(AgentDescriptor::agentId)).toList();
    }

    @Override public List<AgentDescriptor> list() {
        return repository.findAll().stream().map(this::decode)
            .sorted(Comparator.comparing(AgentDescriptor::agentId)).toList();
    }

    @Override @Transactional
    public void register(AgentDescriptor descriptor) {
        if (descriptor == null) throw new IllegalArgumentException("agent descriptor is required");
        AgentComputeDefinition entity = repository.findByAgentId(descriptor.agentId())
            .orElseGet(AgentComputeDefinition::new);
        entity.setAgentId(descriptor.agentId());
        entity.setEnabled(descriptor.enabled());
        try {
            entity.setDescriptorJson(mapper.writeValueAsString(descriptor));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Agent descriptor is not serializable", error);
        }
        repository.save(entity);
    }

    @Override @Transactional
    public void remove(String agentId) {
        if (agentId != null && !agentId.isBlank()) repository.deleteByAgentId(agentId.trim());
    }

    private AgentDescriptor decode(AgentComputeDefinition entity) {
        try {
            return mapper.readValue(entity.getDescriptorJson(), AgentDescriptor.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid persisted agent descriptor " + entity.getAgentId(), error);
        }
    }
}
