package com.chatchat.runtime.skill.application;
import com.chatchat.runtime.skill.api.execution.*;
import com.chatchat.runtime.skill.api.skill.*;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.port.outbound.SkillAnalysisOperator;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SkillAnalysisExecutorTest {
    private final SkillRoleContext identity=new SkillRoleContext("t","u",List.of(),List.of(),Map.of());
    private final SkillDataRequirement data=new SkillDataRequirement("rows","sales.rows.v1",List.of(),false,Map.of());
    @Test void failedStepSkipsDependentsButIndependentAnalysisContinues() {
        var execution=new ArrayList<String>();
        SkillAnalysisOperator operator=new SkillAnalysisOperator() {
            public boolean supports(String value){return true;}
            public SkillStepResult execute(SkillAnalysisStep step,SkillDataResult source,SkillRoleContext identity) {
                execution.add(step.id());if(step.id().equals("fail"))throw new IllegalStateException("offline");
                return new SkillStepResult(step.id(),"COMPLETED",Map.of("value",1),List.of("e"),List.of());
            }
        };
        var requirements=requirements(List.of(step("dependent",List.of("fail")),step("fail",List.of()),step("independent",List.of())));
        var results=new SkillAnalysisExecutor(() -> List.of(operator)).execute(requirements,List.of(
            new SkillDataResult(data,SkillDataResult.Status.AVAILABLE,List.of(Map.of("x",1)),Map.of(),List.of())),identity);
        assertThat(execution).containsExactly("fail","independent");
        assertThat(results).extracting(SkillStepResult::status).containsExactly("FAILED","SKIPPED","COMPLETED");
    }
    @Test void missingDataAndUnknownOperatorsRemainObservable() {
        var executor=new SkillAnalysisExecutor(List::of);
        var req=requirements(List.of(step("count",List.of())));
        assertThat(executor.execute(req,List.of(),identity).get(0).observations()).contains("REQUIRED_DATA_UNAVAILABLE");
        assertThat(executor.execute(req,List.of(new SkillDataResult(data,SkillDataResult.Status.EMPTY,List.of(),Map.of(),List.of())),identity)
            .get(0).observations()).contains("OPERATOR_NOT_REGISTERED");
        assertThatThrownBy(() -> requirements(List.of(step("cycle",List.of("cycle"))))).isInstanceOf(IllegalArgumentException.class);
    }
    private SkillAnalysisStep step(String id,List<String> deps){return new SkillAnalysisStep(id,"COUNT","rows","",deps);}
    private SkillRequirements requirements(List<SkillAnalysisStep> steps) {
        return new SkillRequirements(List.of(),List.of(),List.of(),List.of(),List.of(),List.of(data),steps);
    }
}

