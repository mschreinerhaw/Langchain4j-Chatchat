package com.chatchat.agents.runtime.toolcall;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TemplateParameterCompilationTest {
    final ToolArgumentCompiler compiler=new ToolArgumentCompiler();
    Map<String,Object> schema(Map<String,Object> fields,String...required) {
        return Map.of("type","object","properties",fields,"required",List.of(required),"additionalProperties",false);
    }
    @Test void T01_emptySchemaCompilesToEmptyParameters() {
        assertThat(compiler.compileTemplate(Map.of("unknown","drop"),schema(Map.of())).parameters()).isEmpty();
    }
    @Test void T02_allDefaultsCompileWithoutUserValues() {
        var result=compiler.compileTemplate(Map.of(),schema(Map.of(
            "text",Map.of("type","string","default","catalog"),
            "size",Map.of("type","integer","default",5),
            "enabled",Map.of("type","boolean","default",false),
            "tags",Map.of("type","array","items",Map.of("type","string"),"default",List.of("x")),
            "options",Map.of("type","object","properties",Map.of("active",Map.of("type","boolean")),"default",Map.of("active",true)))));
        assertThat(result.valid()).isTrue();
        assertThat(result.parameters()).containsEntry("size",5).containsEntry("enabled",false).containsEntry("options",Map.of("active",true));
    }
    @Test void T03_validOverrideWinsAndIsNormalized() {
        var result=compiler.compileTemplate(Map.of("limit","12"),schema(Map.of("limit",Map.of("type","integer","default",5,"maximum",20))));
        assertThat(result.valid()).isTrue();assertThat(result.parameters()).containsEntry("limit",12);
        assertThat(result.repairs()).noneMatch(r->r.repairCode().contains("DEFAULT"));
    }
    @Test void T04_optionalMissingDoesNotBlock() {
        assertThat(compiler.compileTemplate(Map.of(),schema(Map.of("unused",Map.of("type","string")))).valid()).isTrue();
    }
    @Test void T05_invalidOptionalOverridesUseValidDefaults() {
        var result=compiler.compileTemplate(Map.of("limit",100,"mode","bad","day","20260230"),schema(Map.of(
            "limit",Map.of("type","integer","minimum",1,"maximum",20,"default",5),
            "mode",Map.of("type","string","enum",List.of("A","B"),"default","A"),
            "day",Map.of("type","string","format","date","default","2026-10-01"))));
        assertThat(result.valid()).isTrue();assertThat(result.parameters()).containsEntry("limit",5).containsEntry("mode","A").containsEntry("day","2026-10-01");
    }
    @Test void T06_invalidOptionalValuesWithoutDefaultsAreOmitted() {
        var result=compiler.compileTemplate(Map.of("limit","invalid","flag","perhaps"),schema(Map.of(
            "limit",Map.of("type","integer"),"flag",Map.of("type","boolean"))));
        assertThat(result.valid()).isTrue();assertThat(result.parameters()).isEmpty();
        assertThat(result.repairs()).hasSize(2);
    }
    @Test void T07_requiredWithDefaultExecutes() {
        var result=compiler.compileTemplate(Map.of(),schema(Map.of("limit",Map.of("type","integer","default",4)),"limit"));
        assertThat(result.valid()).isTrue();assertThat(result.parameters()).containsEntry("limit",4);
    }
    @Test void T08_requiredWithoutValueReturnsStructuredFieldError() {
        var result=compiler.compileTemplate(Map.of(),schema(Map.of("subject",Map.of("type","string")),"subject"));
        assertThat(result.valid()).isFalse();assertThat(result.validationErrors()).singleElement().satisfies(error->{
            assertThat(error.field()).isEqualTo("subject");assertThat(error.errorCode()).isEqualTo("REQUIRED_PARAMETER_MISSING");
            assertThat(error.receivedValue()).isNull();});
    }
    @Test void T10_emptyParametersUseSchemaRules() {
        assertThat(compiler.compileTemplate(Map.of(),schema(Map.of("flag",Map.of("type","boolean","default",true)),"flag")).valid()).isTrue();
        assertThat(compiler.compileTemplate(Map.of(),schema(Map.of("flag",Map.of("type","boolean")),"flag")).valid()).isFalse();
    }
    @Test void T11_unknownNamesNeverReachTheExecutor() {
        var result=compiler.compileTemplate(Map.of("invented",1),Map.of("type","object","properties",Map.of(),"additionalProperties",true));
        assertThat(result.valid()).isTrue();assertThat(result.parameters()).isEmpty();
        assertThat(result.repairs()).singleElement().satisfies(r->assertThat(r.repairCode()).isEqualTo("UNKNOWN_TEMPLATE_PARAMETER_DROPPED"));
    }
    @Test void T12_datesNumbersBooleansArraysAndObjectsUseSchemaTypes() {
        var result=compiler.compileTemplate(Map.of("day","20261027","flag","false","amount","1.25","items",List.of("2","3"),
            "options",Map.of("count","4")),schema(Map.of("day",Map.of("type","string","format","date"),
                "flag",Map.of("type","boolean"),"amount",Map.of("type","number"),
                "items",Map.of("type","array","items",Map.of("type","integer")),
                "options",Map.of("type","object","properties",Map.of("count",Map.of("type","integer"))))));
        assertThat(result.valid()).isTrue();assertThat(result.parameters()).containsEntry("day","2026-10-27")
            .containsEntry("flag",false).containsEntry("items",List.of(2,3)).containsEntry("options",Map.of("count",4));
        assertThat(result.parameters().get("amount")).isEqualTo(new java.math.BigDecimal("1.25"));
        assertThat(compiler.compileTemplate(Map.of("count",1.5),schema(Map.of("count",Map.of("type","integer")),"count")).valid()).isFalse();
    }
    @Test void T15_invalidDefaultsAreConfigurationErrorsEvenWithAValidOverride() {
        for(Object invalid:List.of("5",1.5,"bad")) {
            var result=compiler.compileTemplate(Map.of("count",2),schema(Map.of("count",Map.of("type","integer","default",invalid))));
            assertThat(result.valid()).isFalse();assertThat(result.validationErrors()).anyMatch(e->"INVALID_TEMPLATE_DEFAULT".equals(e.errorCode()));
        }
        var optional=compiler.compileTemplate(Map.of(),schema(Map.of("name",Map.of("type","string","minLength",3,"default","x"))));
        assertThat(optional.valid()).isFalse();
    }
    @Test void nestedFallbacksRetainTheirAuditPaths() {
        var result=compiler.compileTemplate(Map.of("filters",Map.of("count","bad")),schema(Map.of("filters",Map.of(
            "type","object","properties",Map.of("count",Map.of("type","integer","default",3))))));
        assertThat(result.valid()).isTrue();assertThat(result.parameters()).containsEntry("filters",Map.of("count",3));
        assertThat(result.repairs()).anyMatch(r->"filters.count".equals(r.field())&&r.repairCode().contains("DEFAULT"));
    }
    @Test void explicitEmptyAndNullableDefaultsAreNotConfusedWithMissingFields() {
        var field=new java.util.LinkedHashMap<String,Object>();field.put("type",List.of("string","null"));field.put("default",null);
        var result=compiler.compileTemplate(Map.of(),schema(Map.of("text",Map.of("type","string","default",""),"nullable",field),"text","nullable"));
        assertThat(result.valid()).isTrue();assertThat(result.parameters()).containsEntry("text","").containsKey("nullable");
    }
}
