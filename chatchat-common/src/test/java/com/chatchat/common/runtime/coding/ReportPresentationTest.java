package com.chatchat.common.runtime.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReportPresentationTest {
    @Test void defaultsAreAdaptiveAndPreferencesAreFrozenIntoTheExistingContext(){
        assertThat(ReportPresentation.read(Map.of())).containsEntry("strategy","auto");
        var chosen=new HashMap<String,Object>(Map.of("strategy","template","template","evidence-first","density","compact","autoCharts",false,"autoImages",false));
        var context=CodingRuntimeContext.validate(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT",
            "reportMode","visual","reportPresentation",chosen));
        chosen.put("strategy","auto");
        assertThat(((Map<?,?>)context.get("reportPresentation")).get("strategy")).isEqualTo("template");
        assertThat(VisualReportMode.instruction(Map.of("reportPresentation",chosen))).contains("report_layout.v1","SAME report/program","\"strategy\":\"auto\"");
    }
    @Test void rejectsExecutableOrUnknownOptions(){
        for(var bad:List.of(Map.of("strategy","html"),Map.of("autoCharts","true"),Map.of("javascript","alert(1)")))
            assertThatThrownBy(()->ReportPresentation.read(Map.of("reportPresentation",bad))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void layoutRequiresExistingReferencesAndAClosedVocabulary()throws Exception{
        var refs=Map.of("metric",Set.of("m"),"section",Set.of("s"),"chart",Set.of("c"),"table",Set.of("t"));
        var raw=Map.of("schemaVersion","report_layout.v1","blocks",List.of(Map.of("id","first","type","chart","ref","c","span","full")));
        assertThat(ReportLayout.validate(raw,refs)).isEqualTo(raw);
        var nullHint=new HashMap<String,Object>(Map.of("id","bad","type","summary"));nullHint.put("span",null);
        assertThatThrownBy(()->ReportLayout.validate(Map.of("schemaVersion","report_layout.v1","blocks",List.of(nullHint)),refs)).isInstanceOf(IllegalArgumentException.class);
        for(var block:List.of(Map.of("id","bad","type","script"),Map.of("id","bad","type","chart","ref","other"),
            Map.of("id","bad","type","section","ref","s","html","<script>")))
            assertThatThrownBy(()->ReportLayout.validate(Map.of("schemaVersion","report_layout.v1","blocks",List.of(block)),refs)).isInstanceOf(IllegalArgumentException.class);
        try(var input=getClass().getResourceAsStream("/runtime/report-layout.schema.json")){
            var types=new HashSet<String>();new ObjectMapper().readTree(input).at("/properties/blocks/items/properties/type/enum").forEach(value->types.add(value.asText()));
            assertThat(types).isEqualTo(ReportLayout.TYPES);
        }
    }
}
