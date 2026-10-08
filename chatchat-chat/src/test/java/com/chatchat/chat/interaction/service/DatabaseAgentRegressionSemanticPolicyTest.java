package com.chatchat.chat.interaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseAgentRegressionSemanticPolicyTest {

    @Test
    void loadsCaseSpecificVocabularyFromDatabase() {
        AgentRegressionSemanticProfileRepository repository = mock(AgentRegressionSemanticProfileRepository.class);
        AgentRegressionSemanticProfileEntity row = new AgentRegressionSemanticProfileEntity();
        row.setProfileKey("graph_case");
        row.setConnectorTermsJson("[\"graph gateway\"]");
        row.setRelationRulesJson("[{\"source\":\"graph gateway\",\"target\":\"index\","
            + "\"relation\":\"queries\",\"sourceLabel\":\"Graph Gateway\",\"targetLabel\":\"Index\"}]");
        when(repository.findById("graph_case")).thenReturn(Optional.of(row));

        var profile = new DatabaseAgentRegressionSemanticPolicy(repository, new ObjectMapper())
            .profile("graph_case");

        assertThat(profile.connectorTerms()).containsExactly("graph gateway");
        assertThat(profile.relationRules()).hasSize(1);
        assertThat(profile.relationRules().get(0).relation()).isEqualTo("queries");
    }
}
