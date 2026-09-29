package com.chatchat.chat.skills.runtime;
import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.runtime.skill.api.execution.SkillCompositionRequest;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.skill.SkillDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ModelSkillIntentPlannerTest {
    @Test void acceptsKnownCapabilitiesAndRejectsInventedOnes() {
        var model = mock(ChatModel.class);
        var planner = new ModelSkillIntentPlanner(model, mock(ConfigurableChatModelFactory.class), new ObjectMapper());
        try {
            var request = new SkillCompositionRequest("Analyze", new SkillRoleContext("t", "u", List.of(), List.of(), Map.of()),
                List.of(), List.of(), Map.of(), Map.of(), "GOOGLE_ADK_NATIVE", Map.of(), 4);
            var catalog = List.of(new SkillDescriptor("a", "v1", "a", "summary", "finance", "DATABASE", "", "", "", 1,
                Map.of("capabilities", List.of("finance.summary"))));
            when(model.chat(anyString())).thenReturn("{\"domain\":\"finance\",\"objective\":\"summary\",\"tasks\":[{\"objective\":\"summary\",\"capabilities\":[\"finance.summary\"]}]}");
            assertThat(planner.understand(request, catalog).capabilities()).containsExactly("finance.summary");
            when(model.chat(anyString())).thenReturn("{\"tasks\":[{\"capabilities\":[\"admin.execute\"]}]}");
            var rejected = planner.understand(request, catalog);
            assertThat(rejected.mode()).isEqualTo("PLANNING_UNAVAILABLE");
            assertThat(rejected.capabilities()).isEmpty();
        } finally { planner.close(); }
    }
}
