package com.chatchat.api.runtime.coding.analysis;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingNativeAnalysisObjectiveTest {
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
