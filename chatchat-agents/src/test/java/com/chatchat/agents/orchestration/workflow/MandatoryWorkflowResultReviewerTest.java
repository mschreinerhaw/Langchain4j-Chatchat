package com.chatchat.agents.orchestration.workflow;

import com.chatchat.agents.orchestration.evidence.AgentToolResultFactExtractor;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.orchestration.tool.ToolObservationBuilder;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MandatoryWorkflowResultReviewerTest {
    @Test void publisherRoleIdentifiesOpaqueDiscoveryNamesWithoutBusinessKeywords() {
        var names=mock(AgentToolNameResolver.class);
        when(names.isTemplateDiscoveryToolName("opaque")).thenReturn(true);
        var result=reviewer(names).review("opaque",ToolOutput.success(Map.of(
            "templates",List.of(Map.of("templateId","real")),"returnedCount",1)));
        assertThat(result).containsEntry("satisfied",true).containsEntry("resultCode","TEMPLATE_MATCHED");
    }

    @Test void aDeclaredCountCannotReplaceAnActuallyEmptyCandidateSet() {
        var names=mock(AgentToolNameResolver.class);
        when(names.isTemplateDiscoveryToolName("opaque")).thenReturn(true);
        for(Object payload:List.of(Map.of("templates",List.of(),"returnedCount",8),
                Map.of("returnedCount",8,"preview",Map.of("templates",List.of())))) {
            assertThat(reviewer(names).review("opaque",ToolOutput.success(payload)))
                .containsEntry("satisfied",false).containsEntry("returnedCount",0);
        }
    }

    private MandatoryWorkflowResultReviewer reviewer(AgentToolNameResolver names) {
        return new MandatoryWorkflowResultReviewer(names,mock(AgentToolResultFactExtractor.class),
            mock(ToolObservationBuilder.class),new ObjectMapper());
    }
}
