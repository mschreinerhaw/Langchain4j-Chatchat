package com.chatchat.agents.orchestration.analysis.context;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.*;
import org.junit.jupiter.api.Test;

class ToolReviewPromptContextTest {
    final ObjectMapper mapper=new ObjectMapper();
    final ToolReviewPromptContext projector=new ToolReviewPromptContext(mapper);
    @Test void sharesRepeatedSchemasWithoutLosingCandidateTailOrChangingValues()throws Exception {
        List<Object> candidates=new ArrayList<>();
        Map<String,Object> schema=Map.of("description","含引号\"、换行\n和不同空白  ".repeat(200),"nullable",true,"precision",1.25);
        for(int i=0;i<40;i++)candidates.add(Map.of("id","candidate-"+i,"schema",schema,"meaning","unique-tail-"+i));
        Map<String,Object> input=Map.of("candidates",candidates);
        var result=projector.project(input);JsonNode envelope=mapper.readTree(result.json());
        assertThat(decode(envelope.get("context"),envelope.get("sharedValues"))).isEqualTo(mapper.valueToTree(input));
        assertThat(result.projectedChars()).isLessThan(result.originalChars()/4);
        assertThat(result.json()).contains("candidate-39","unique-tail-39");
    }
    @Test void doesNotExpandSmallUniqueRequests(){var p=projector.project(Map.of("one","value","two",List.of(1,2,3)));assertThat(p.projectedChars()).isEqualTo(p.originalChars());assertThat(p.sharedValues()).isZero();}
    @Test void dictionariesAreRequestLocal(){projector.project(List.of("private-run-a".repeat(300),"private-run-a".repeat(300)));assertThat(projector.project(Map.of("new","run-b")).json()).doesNotContain("private-run-a");}
    @Test void literalReservedKeysCannotBeMistakenForRuntimeReferences()throws Exception {
        Object input=Map.of("copies",List.of("shared".repeat(300),"shared".repeat(300)),"literal",Map.of("$runtimeReviewRef","context-0"));
        JsonNode e=mapper.readTree(projector.project(input).json());assertThat(decode(e.get("context"),e.get("sharedValues"))).isEqualTo(mapper.valueToTree(input));
    }
    JsonNode decode(JsonNode node,JsonNode values){
        if(node.isObject()&&node.size()==1&&node.has("$runtimeReviewLiteral"))return node.get("$runtimeReviewLiteral");
        if(node.isObject()&&node.size()==1&&node.has("$runtimeReviewRef"))return values.get(node.get("$runtimeReviewRef").asText());
        if(node.isObject()){ObjectNode r=mapper.createObjectNode();node.fields().forEachRemaining(e->r.set(e.getKey(),decode(e.getValue(),values)));return r;}
        if(node.isArray()){ArrayNode r=mapper.createArrayNode();node.forEach(c->r.add(decode(c,values)));return r;}
        return node;
    }
}
