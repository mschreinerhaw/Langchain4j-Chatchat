package com.chatchat.api.runtime.coding.analysis;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingNativeAnalysisObjectiveTest {
    @Test void nativeVisualTasksRetainTheCompleteExistingVisualAndPresentationContract() throws Exception {
        var attributes=java.util.Map.<String,Object>of("reportMode","visual","reportPresentation",java.util.Map.of("strategy","concise","autoImages",false));
        var chunks=CodingNativeAnalysisObjective.contextInstructions(java.util.Map.of("source","unchanged"),java.util.Map.of(),attributes);
        com.chatchat.common.runtime.coding.CodingRuntimeContext.validate(java.util.Map.of(
            "schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","analysisInstructions",chunks));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var restored=json.readTree(String.join("",chunks.subList(1,chunks.size())));
        assertThat(restored.path("visualReportContract").asText()).isEqualTo(
            com.chatchat.common.runtime.coding.VisualReportMode.instruction(attributes));
        var off=CodingNativeAnalysisObjective.contextInstructions(java.util.Map.of(),java.util.Map.of());
        assertThat(json.readTree(String.join("",off.subList(1,off.size()))).has("visualReportContract")).isFalse();
    }
    @Test void sourceDeclarationsRoundTripThroughTheExistingStrictContextWithoutNewFields() throws Exception {
        var source=java.util.Map.<String,Object>of("declaration","原始字段😀".repeat(3000));
        var binding=java.util.Map.<String,Object>of("customerNo","070200046604");
        var chunks=CodingNativeAnalysisObjective.contextInstructions(source,binding);
        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks.get(0)).isEqualTo(CodingNativeAnalysisObjective.CONTEXT_PROFILE);
        var context=com.chatchat.common.runtime.coding.CodingRuntimeContext.validate(java.util.Map.of(
            "schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","analysisInstructions",chunks));
        assertThat(context).containsEntry("analysisInstructions",chunks);
        var restored=new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.join("",chunks.subList(1,chunks.size())));
        assertThat(restored.path("sourceRepresentation").path("declaration").asText()).isEqualTo(source.get("declaration"));
        assertThat(restored.path("templateParameterBinding").path("customerNo").asText()).isEqualTo("070200046604");
    }
    @Test void retainsTheExactUserObjectiveWithoutAddingUnrequestedDomainOrTransportWork(){
        String user="按客户指定的 value 字段计算，不推测货币单位。\n保留用户的原始约束。";
        String prompt=CodingNativeAnalysisObjective.compose(user,List.of("python_execute"),false,false);
        assertThat(prompt).startsWith(user+"\n\n").hasSizeLessThan(2600)
            .contains("nativePath","virtualPath","datasetReference","runtime-inputs.json","STOP","actual Managed Python execution")
            .doesNotContain("Workbook","in-memory SQLite","trading calendar","web_search","market_resolve_security");
        assertThat(prompt.indexOf(user)).isEqualTo(prompt.lastIndexOf(user));
    }
    @Test void addsOnlyTheAlreadyAuthorizedResourceAndVisualRequirements(){
        String base=CodingNativeAnalysisObjective.compose("Task",List.of(),false,true);
        String scoped=CodingNativeAnalysisObjective.compose("Task",List.of("market_get_day_snapshot","web_search"),true,false);
        assertThat(base).doesNotContain("Resource scope:","Internet scope:","Visual delivery:","This is a computation task:");
        assertThat(scoped).contains("Resource scope:","Internet scope:","Visual delivery:","This is a computation task:");
    }
}
