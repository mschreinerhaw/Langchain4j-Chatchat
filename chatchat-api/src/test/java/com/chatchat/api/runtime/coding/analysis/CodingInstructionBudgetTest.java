package com.chatchat.api.runtime.coding.analysis;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingInstructionBudgetTest {
    @Test void preservesTheCompleteAppendixInTheExistingConfigurationWhenDuplicatesAreInsufficient() {
        String objective="User objective "+"x".repeat(22000);
        String appendix="\nScope and definitions: "+"\uD83D\uDCCA rules ".repeat(4000);
        var configuration=new java.util.LinkedHashMap<String,Object>(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT"));
        String compact=CodingInstructionBudget.fit(objective,objective+appendix,List.of(),configuration);
        assertThat(compact).startsWith(objective).contains("runtimeContext.analysisInstructions");
        var frozen=com.chatchat.common.runtime.coding.CodingRuntimeContext.validate(configuration);
        assertThat(String.join("",(List<String>)frozen.get("analysisInstructions"))).isEqualTo(appendix);
        assertThat(compact.codePointCount(0,compact.length())).isLessThanOrEqualTo(32768);
    }
    @Test void preservesTheUserObjectiveAndUnicodeWhileDeferringOnlyDuplicateMetadata() {
        String block="Environment: "+"package-version-".repeat(1500);
        String objective="用户原始目标 🧮\n"+block;
        String policy="\nKeep scope, exact decimals and result validation.\n";
        String compact=CodingInstructionBudget.fit(objective,objective+policy+block,
            List.of(new CodingInstructionBudget.Metadata(block,"See runtimeContext.pythonEnvironment")));
        assertThat(compact).startsWith(objective+policy).endsWith("See runtimeContext.pythonEnvironment");
        assertThat(compact.codePointCount(0,compact.length())).isLessThanOrEqualTo(32768);
        assertThat(new com.chatchat.common.runtime.coding.CodingTaskContract.Objective(compact,
            List.of("rows.jsonl"),List.of("validation:captured")).instruction()).isEqualTo(compact);
    }

    @Test void keepsSmallInstructionsAndRejectsOversizedObjectivesWithoutTruncation() {
        assertThat(CodingInstructionBudget.fit("Analyze", "Analyze\nmetadata",List.of(
            new CodingInstructionBudget.Metadata("metadata","pointer")))).isEqualTo("Analyze\nmetadata");
        String large="🧮".repeat(32769);
        assertThatThrownBy(()->CodingInstructionBudget.fit(large,large,List.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32769");
    }
}
