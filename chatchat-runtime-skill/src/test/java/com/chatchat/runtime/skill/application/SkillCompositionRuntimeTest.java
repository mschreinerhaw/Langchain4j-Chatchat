package com.chatchat.runtime.skill.application;
import com.chatchat.runtime.skill.api.execution.*;
import com.chatchat.runtime.skill.api.discovery.*;
import com.chatchat.runtime.skill.api.identity.*;
import com.chatchat.runtime.skill.api.resolution.*;
import com.chatchat.runtime.skill.api.resource.*;
import com.chatchat.runtime.skill.api.skill.*;
import com.chatchat.runtime.skill.port.inbound.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SkillCompositionRuntimeTest {
    private final SkillRoleContext identity=new SkillRoleContext("t","u",List.of(),List.of(),Map.of());
    @Test void ordersCapabilityDependenciesPinsVersionsAndKeepsIndependentFailuresLocal() {
        var input=descriptor("input","data.prepare",List.of());
        var dependent=descriptor("summary","sales.summary",List.of("data.prepare"));
        var independent=descriptor("other","sales.other",List.of());
        var invoked=new ArrayList<String>();
        var composition=runtime(List.of(dependent,input,independent),request -> {
            String id=request.requestedSkillIds().get(0);invoked.add(id);
            assertThat(request.intent()).containsEntry("skillVersion","v2");
            if(id.equals("input"))throw new IllegalStateException("unavailable");
            return new SkillExecutionResult("COMPLETED",null,null,null,null,Map.of());
        });
        var result=composition.execute(request(List.of("sales.summary","sales.other")));
        assertThat(result.plan().selections()).extracting(SkillCompositionPlan.Selection::skillId).containsExactly("other","input","summary");
        assertThat(invoked).containsExactly("other","input");
        assertThat(result.skipped()).containsEntry("input","SKILL_EXECUTION_FAILED")
            .containsEntry("summary","CAPABILITY_DEPENDENCY_NOT_COMPLETED");
        assertThat(result.status()).isEqualTo("COMPLETED_WITH_LIMITATIONS");
    }
    @Test void detectsCapabilityCycleAndMissingProvidersWithoutInvokingRuntime() {
        var composition=runtime(List.of(descriptor("a","a",List.of("b")),descriptor("b","b",List.of("a"))),
            request -> {throw new AssertionError("cycle must not execute");});
        assertThat(composition.plan(request(List.of("a"))).selections()).isEmpty();
        assertThat(composition.plan(request(List.of("unavailable"))).missingCapabilities()).contains("unavailable");
    }
    private SkillCompositionRequest request(List<String> capabilities) {
        return new SkillCompositionRequest("analyze",identity,capabilities,List.of(),Map.of(),Map.of(),"LANGCHAIN4J",Map.of(),4);
    }
    private SkillDescriptor descriptor(String id,String capability,List<String> dependencies) {
        return new SkillDescriptor(id,"v2",id,"","sales","DATABASE","source","","",1,
            Map.of("capabilities",List.of(capability),"requiresCapabilities",dependencies));
    }
    private SkillCompositionRuntime runtime(List<SkillDescriptor> descriptors,SkillRuntime execution) {
        SkillResolver resolver=new SkillResolver(){
            public SkillResolution resolve(SkillResolutionRequest request) {
                var descriptor=descriptors.stream().filter(item -> item.id().equals(request.skillId())).findFirst().orElseThrow();
                return new SkillResolution(new ResolvedSkill(descriptor,"instructions",List.of(),null,Map.of()),
                    new AuthorizedSkillScope(true,List.of(),List.of(),List.of(),List.of(),List.of("fixed"),List.of()),"db","RESOLVED",Map.of());
            }
            public Optional<SkillResourceContent> readResource(SkillResourceRequest request){return Optional.empty();}
        };
        return new SkillCompositionRuntime(request -> new SkillRouteResult(descriptors,"ROUTED",Map.of()),resolver,
            new DefaultWorkflowResolver(),execution);
    }
}

