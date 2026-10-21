package com.chatchat.agents.orchestration.analysis.governance;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.common.runtime.capability.ProblemAnalysisPlan;
import com.chatchat.common.tool.ToolOutput;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FileAnalysisDependenciesTest {
    Map<String,Object> attributes(ProblemAnalysisPlan.ToolDataDependency dependency) {
        var file=new ProblemAnalysisPlan.FileInput("one.xlsx","user-asset:"+"a".repeat(64)+":"+"b".repeat(64),"application/xlsx",42,"b".repeat(64));
        return Map.of("problemAnalysisPlan",new ProblemAnalysisPlan(ProblemAnalysisPlan.Status.READY,"analyze","files","data","source dependency",
            List.of(new ProblemAnalysisPlan.Task("analyze",ProblemAnalysisPlan.Intent.DATA_ANALYSIS,List.of("files"),"quality report")),"",
            new ProblemAnalysisPlan.FileAnalysis(List.of(file),dependency)));
    }
    InterpretationPlanRuntime.ExecutionResult result(ToolOutput output,String outcome,boolean approval) {
        var execution=new ToolRuntimeExecution(output,null,null,outcome,Map.of());
        var step=new InterpretationPlanRuntime.StepExecution(1,"tool_call","opaque",false,Map.of("rows",List.of()),
            "Nonempty result review not satisfied",execution,null,1);
        return new InterpretationPlanRuntime.ExecutionResult("failed",false,approval,"review rejected",null,List.of(step),Map.of(),1);
    }
    @Test void successfulEmptyQueryCanBeOptionalEvenWhenItsNonemptyWorkflowReviewRejectedIt() {
        var empty=result(ToolOutput.success(Map.of("rows",List.of())),"success",false);
        assertThat(FileAnalysisDependencies.optionalEmpty(attributes(ProblemAnalysisPlan.ToolDataDependency.OPTIONAL),empty)).isTrue();
        assertThat(FileAnalysisDependencies.optionalEmpty(attributes(ProblemAnalysisPlan.ToolDataDependency.REQUIRED),empty)).isFalse();
        assertThat(FileAnalysisDependencies.optionalEmpty(Map.of(),empty)).isFalse();
    }
    @Test void failureConfirmationMalformedAndNonemptyObservationsNeverBecomeAnEmptyBranch() {
        var attributes=attributes(ProblemAnalysisPlan.ToolDataDependency.OPTIONAL);
        assertThat(FileAnalysisDependencies.optionalEmpty(attributes,result(ToolOutput.failure("access denied"),"failed",false))).isFalse();
        assertThat(FileAnalysisDependencies.optionalEmpty(attributes,result(ToolOutput.success(Map.of()),"confirmation_required",false))).isFalse();
        assertThat(FileAnalysisDependencies.optionalEmpty(attributes,result(ToolOutput.success(Map.of()),"success",true))).isFalse();
        assertThat(FileAnalysisDependencies.optionalEmpty(attributes,result(ToolOutput.success(Map.of("rows",List.of(Map.of("id",1)))),"success",false))).isFalse();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"asset_query_result.v1,assets","template_query_result.v1,templates"})
    void emptyDiscoveryContractsKeepControlMetadataWithoutTreatingItAsBusinessData(String schema,String key) {
        var attributes=attributes(ProblemAnalysisPlan.ToolDataDependency.OPTIONAL);
        var empty=Map.<String,Object>of("schemaVersion",schema,"success",true,"returnedCount",0,key,List.of(),
            "filters",Map.of("query","requested source"),"possiblyTruncated",false);
        assertThat(FileAnalysisDependencies.optionalEmpty(attributes,result(ToolOutput.success(empty),"success",false))).isTrue();
        var nonempty=new HashMap<>(empty);nonempty.put(key,List.of(Map.of("id","candidate")));nonempty.put("returnedCount",1);
        assertThat(FileAnalysisDependencies.optionalEmpty(attributes,result(ToolOutput.success(nonempty),"success",false))).isFalse();
        var failed=Map.of("success",false,"error","access denied","data",empty);
        assertThat(FileAnalysisDependencies.optionalEmpty(attributes,result(ToolOutput.success(failed),"success",false))).isFalse();
    }
    @Test void storedAcceptedPlanRetainsItsReferencesAndDependency() {
        var original=attributes(ProblemAnalysisPlan.ToolDataDependency.REQUIRED);
        var restored=new com.fasterxml.jackson.databind.ObjectMapper().convertValue(original,Map.class);
        assertThat(FileAnalysisDependencies.files(restored)).isEqualTo(FileAnalysisDependencies.files(original));
    }
}
