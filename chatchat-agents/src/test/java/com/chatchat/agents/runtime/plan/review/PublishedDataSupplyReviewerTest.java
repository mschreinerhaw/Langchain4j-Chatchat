package com.chatchat.agents.runtime.plan.review;

import com.chatchat.common.mcp.capability.*;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.agents.runtime.plan.selection.EvidenceBasedTemplateCandidateEvaluator;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PublishedDataSupplyReviewerTest {
    ToolMetadata tool(boolean declared) {
        var meta=new LinkedHashMap<String,Object>();
        meta.put(McpTemplateSelectionScope.METADATA_KEY,McpTemplateSelectionScope.fixedBinding("fixture").toMetadata());
        if(declared)meta.put(McpDataSupplyPolicy.METADATA_KEY,McpDataSupplyPolicy.fixedBaseline());
        return ToolMetadata.builder().metadata(meta).build();
    }
    Map<String,Object> page() {return new LinkedHashMap<>(Map.of("success",true,"scopeMode","FIXED_BINDING",
        "bindingComplete",true,McpDataSupplyPolicy.METADATA_KEY,McpDataSupplyPolicy.fixedBaseline(),"hasMore",false));}
    Map<String,Object> template(String id) {return Map.of("templateId",id,"parameterSchema",Map.of("type","object","properties",Map.of()));}
    @Test void collectsEightAuthorizedSourcesWithoutClaimingSemanticCoverage() {
        var candidates=java.util.stream.IntStream.range(0,8).mapToObj(i->template("source-"+i)).toList();
        var review=PublishedDataSupplyReviewer.review(tool(true),page(),candidates).orElseThrow();
        var metadata=new LinkedHashMap<>(review.metadata());metadata.put("originalUserQuestion","Assess the returned evidence");
        var evaluated=new EvidenceBasedTemplateCandidateEvaluator().evaluate(Map.of("templates",candidates,"hasMore",false),metadata);
        assertThat(evaluated.applied()).isTrue();assertThat(evaluated.selectedIds()).hasSize(8);
        assertThat(evaluated.output().toString()).contains("runtime_published_data_supply");
        assertThat(review.metadata()).containsEntry("analyticalCoverage","NOT_EVALUATED")
            .containsEntry("coverageDecision","SCOPE_INSUFFICIENT");
        assertThat(evaluated.templateMatchAnalysis().toString()).contains("RUNTIME_PUBLISHED_DATA_SUPPLY")
            .doesNotContain("Selected by context-aware reviewer");
    }
    @Test void responseOnlyDeclarationsDoNotAuthorizeExecutionAndTrustedPolicyAcceptsLegacyResponseShape() {
        assertThat(PublishedDataSupplyReviewer.review(tool(false),page(),List.of(template("a")))).isEmpty();
        var response=page();response.remove(McpDataSupplyPolicy.METADATA_KEY);
        assertThat(PublishedDataSupplyReviewer.review(tool(true),response,List.of(template("a")))).isPresent();
    }
    @Test void failedOrIncompletePublicationCannotBeAdmitted() {
        for(String field:List.of("success","bindingComplete")) {
            var response=page();response.put(field,false);
            assertThat(PublishedDataSupplyReviewer.review(tool(true),response,List.of(template("a")))).isEmpty();
        }
    }
    @Test void parameterizedAndUnspecifiedSchemasAreDeferredWithoutInventingArguments() {
        var candidates=List.of(template("baseline"),Map.<String,Object>of("templateId","needs-input","parameterSchema",
            Map.of("type","object","properties",Map.of("entity",Map.of("type","string")),"required",List.of("entity"))),Map.<String,Object>of("templateId","unknown"));
        var review=PublishedDataSupplyReviewer.review(tool(true),page(),candidates).orElseThrow();
        assertThat(review.metadata()).containsEntry("selectedTemplateIds",List.of("baseline"))
            .containsEntry("deferredTemplateIds",List.of("needs-input","unknown"));
        assertThat(PublishedDataSupplyReviewer.review(tool(true),page(),candidates.subList(1,3))).isEmpty();
    }
    @Test void duplicateIdentitiesAndSchemaReferencesFailClosed() {
        assertThat(PublishedDataSupplyReviewer.review(tool(true),page(),List.of(template("a"),template("a")))).isEmpty();
        assertThat(McpDataSupplyPolicy.parameterFree(Map.of("parameterSchema",Map.of("type","object","$ref","#/$defs/input")))).isFalse();
    }
}
