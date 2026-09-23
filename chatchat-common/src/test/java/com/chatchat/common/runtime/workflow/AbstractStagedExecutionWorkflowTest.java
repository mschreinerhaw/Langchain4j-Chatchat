package com.chatchat.common.runtime.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractStagedExecutionWorkflowTest {

    @Test
    void fixesAnalysisExecutionLifecycleOrder() {
        List<String> stages = new ArrayList<>();
        AbstractStagedExecutionWorkflow<String, String, String, String, String> workflow =
            new AbstractStagedExecutionWorkflow<>() {
                @Override public String workflowId() { return "test.staged"; }
                @Override protected String analyze(String input, KernelDataScope scope) {
                    stages.add("analyze"); return input;
                }
                @Override protected String plan(String input, String analysis, KernelDataScope scope) {
                    stages.add("plan"); return analysis;
                }
                @Override protected String executePlan(String input, String analysis, String plan,
                                                       KernelDataScope scope) {
                    stages.add("execute"); return plan;
                }
                @Override protected void verify(String input, String analysis, String plan,
                                                String execution, KernelDataScope scope) {
                    stages.add("verify");
                }
                @Override protected String assemble(String input, String analysis, String plan,
                                                    String execution, KernelDataScope scope) {
                    stages.add("assemble"); return execution;
                }
            };

        assertThat(workflow.execute("query")).isEqualTo("query");
        assertThat(stages).containsExactly("analyze", "plan", "execute", "verify", "assemble");
    }
}
