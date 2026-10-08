package com.chatchat.chat.interaction.service;

import com.chatchat.agents.runtime.evaluation.regression.AgentRegressionSemanticPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Loads the current case-specific or default regression vocabulary from the database. */
@Component
@RequiredArgsConstructor
public class DatabaseAgentRegressionSemanticPolicy implements AgentRegressionSemanticPolicy {

    private static final String DEFAULT_PROFILE = "default";
    private final AgentRegressionSemanticProfileRepository profiles;
    private final ObjectMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public Profile profile(String caseId) {
        AgentRegressionSemanticProfileEntity row =
            (caseId == null || caseId.isBlank() ? java.util.Optional.<AgentRegressionSemanticProfileEntity>empty()
                : profiles.findById(caseId))
                .or(() -> profiles.findById(DEFAULT_PROFILE)).orElse(null);
        if (row == null) return new Profile(List.of(), List.of());
        try {
            List<String> connectors = mapper.readValue(row.getConnectorTermsJson(), new TypeReference<>() { });
            List<RelationRule> relations = mapper.readValue(row.getRelationRulesJson(), new TypeReference<>() { });
            return new Profile(connectors, relations);
        } catch (Exception invalidProfile) {
            throw new IllegalStateException("Invalid Agent regression semantic profile: " + row.getProfileKey(),
                invalidProfile);
        }
    }
}
