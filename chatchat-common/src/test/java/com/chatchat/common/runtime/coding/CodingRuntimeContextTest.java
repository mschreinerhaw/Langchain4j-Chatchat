package com.chatchat.common.runtime.coding;

import java.util.*;
import org.junit.jupiter.api.Test;
import static com.chatchat.common.runtime.coding.CodingContractTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class CodingRuntimeContextTest {
    @Test void negativeInternetAvailabilityIsDiagnosticAndCannotGrantToolsOrClaimAServiceProbe() {
        var status=new HashMap<String,Object>(Map.of("schemaVersion","runtime_os.internet.availability.v1",
            "status","UNAVAILABLE","reasonCode","MODEL_INTERNET_DISABLED","serviceChecked",false,"toolsAvailable",false));
        var context=new HashMap<String,Object>(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","internetAvailability",status));
        assertThat(CodingRuntimeContext.validate(context)).doesNotContainKey("internetSearch");
        status.put("serviceChecked",true);assertThatThrownBy(()->CodingRuntimeContext.validate(context)).isInstanceOf(CodingContractException.class);
        status.put("serviceChecked",false);status.put("toolsAvailable",true);status.put("status","AVAILABLE");
        assertThatThrownBy(()->CodingRuntimeContext.validate(context)).isInstanceOf(CodingContractException.class);
        status.put("toolsAvailable",false);status.put("status","UNAVAILABLE");status.put("endpoint","https://private.invalid");
        assertThatThrownBy(()->CodingRuntimeContext.validate(context)).isInstanceOf(CodingContractException.class);
    }
    @Test void visualQualityRequirementsStillFitTheExistingTaskInstructionLimit() {
        assertThat(16000+VisualReportMode.instruction(Map.of()).length()).isLessThanOrEqualTo(32768);
    }
    @Test void authorizedSkillMetadataAndIdentityListsHaveNoFixedPackageCount() {
        var ids=java.util.stream.IntStream.range(0,150).mapToObj(i->"authorized-"+i).toList();
        var cards=ids.stream().map(id->Map.<String,Object>of("id",id,"name",id,"version","v1")).toList();
        var context=CodingRuntimeContext.validate(Map.of("schemaVersion","runtime_os.coding.context.v1",
            "consistency","TASK_SNAPSHOT","configuredSkillIds",ids,"skills",Map.of("skills",cards)));
        assertThat((List<?>)context.get("configuredSkillIds")).hasSize(150);
        assertThat((List<?>)((Map<?,?>)context.get("skills")).get("skills")).hasSize(150);
        assertThatThrownBy(()->((List<Object>)context.get("configuredSkillIds")).add("other"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void skillCountExceptionCannotBypassOtherContextSafetyBounds() {
        var base=new HashMap<String,Object>(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT"));
        base.put("model",Map.of("unexpectedList",Collections.nCopies(129,"value")));
        assertThatThrownBy(()->CodingRuntimeContext.validate(base)).isInstanceOf(CodingContractException.class);
        base.remove("model");
        for(String key:List.of("skills.skills","skills/skills","skills~1skills")) {
            base.put(key,Collections.nCopies(129,Map.of("id","value")));
            assertThatThrownBy(()->CodingRuntimeContext.validate(base)).isInstanceOf(CodingContractException.class);
            base.remove(key);
        }
        for(var cards:List.of(List.of(Map.of("apiKey","private")),List.of(Map.of("description","x".repeat(16001))),List.of("invalid-card"))) {
            base.put("skills",Map.of("skills",cards));
            assertThatThrownBy(()->CodingRuntimeContext.validate(base)).isInstanceOf(CodingContractException.class);
        }
        base.remove("skills");base.put("configuredSkillIds",List.of(Map.of("id","invalid")));
        assertThatThrownBy(()->CodingRuntimeContext.validate(base)).isInstanceOf(CodingContractException.class);
    }
    @Test void multipleSearchEnginesAreFrozenWithoutCredentialsAndLegacySingleEngineStillLoads(){
        var internet=new HashMap<String,Object>(Map.of("schemaVersion","runtime_os.internet.v1","modelName","model","modelRevision",2,
            "providerId","one","providerRevision",3,"agentId","agent","explicitlyRequested",false));
        var context=new HashMap<String,Object>(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","internetSearch",internet));
        assertThat(CodingRuntimeContext.validate(context)).containsKey("internetSearch");
        var one=Map.of("id","one","name","First","revision",3);var two=Map.of("id","two","name","Second","revision",5);
        internet.put("providers",List.of(one,two));assertThat(CodingRuntimeContext.validate(context)).containsKey("internetSearch");
        internet.put("providers",List.of(one,one));assertThatThrownBy(()->CodingRuntimeContext.validate(context)).isInstanceOf(CodingContractException.class);
        internet.put("providers",List.of(two,one));assertThatThrownBy(()->CodingRuntimeContext.validate(context)).isInstanceOf(CodingContractException.class);
        internet.put("providers",List.of(Map.of("id","one","name","First","revision",3,"apiKey","secret")));
        assertThatThrownBy(()->CodingRuntimeContext.validate(context)).isInstanceOf(CodingContractException.class);
    }
    @Test void resourceProfileSurvivesTheActualCanonicalContractAndRejectsPhysicalOrIdentityFields() throws Exception {
        var node=fixture("task");node.put("schemaVersion",CodingProtocol.ANALYSIS_VERSION);
        var profile=Map.of("schemaVersion","runtime_os.knowledge.v1","agentId","agent","resourceIds",List.of("financial_news","financial_market"),"explicitlyRequested",true);
        node.set("runtimeContext",JSON.valueToTree(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","knowledgeExploration",profile)));
        var contract=JSON.treeToValue(node,CodingTaskContract.class);
        assertThat(contract.runtimeContext().get("knowledgeExploration")).isEqualTo(profile);
        var invalid=new HashMap<String,Object>(profile);invalid.put("resourceIds",List.of("runtime-news-*"));
        assertThatThrownBy(()->CodingRuntimeContext.validate(Map.of("knowledgeExploration",invalid))).isInstanceOf(CodingContractException.class);
        invalid.put("resourceIds",List.of("financial_news"));invalid.put("userId","other-user");
        assertThatThrownBy(()->CodingRuntimeContext.validate(Map.of("knowledgeExploration",invalid))).isInstanceOf(CodingContractException.class);
    }
    @Test void fileInputCapabilitiesAreVersionedAndRestrictedToAnalysisProfile() throws Exception {
        var node=fixture("task");
        node.set("runtimeContext",JSON.valueToTree(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT",
            "inputFiles",Map.of("schemaVersion",CodingInputManifest.VERSION,"format","PARQUET","delivery","SHARED_DIRECTORY",
                "manifestFileId","c"+"a".repeat(63),"manifestSha256","a".repeat(64)))));
        assertThatThrownBy(()->JSON.treeToValue(node,CodingTaskContract.class)).hasRootCauseInstanceOf(CodingContractException.class);
        node.put("schemaVersion",CodingProtocol.ANALYSIS_VERSION);
        ((com.fasterxml.jackson.databind.node.ObjectNode)node.get("authorization")).set("allowedDataRefs",JSON.valueToTree(List.of("c"+"a".repeat(63))));
        assertThat(JSON.treeToValue(node,CodingTaskContract.class).runtimeContext()).containsKey("inputFiles");
    }
    @Test void legacyContractCanonicalBytesAndHashRemainStable() throws Exception {
        var task = JSON.treeToValue(fixture("task"), CodingTaskContract.class);
        assertThat(task.runtimeContext()).isEmpty();
        assertThat(CodingCanonicalHash.canonicalJson(task)).doesNotContain("runtimeContext");
        assertThat(CodingCanonicalHash.sha256(task)).isEqualTo(CodingCanonicalHash.sha256(JSON.treeToValue(tree(task),CodingTaskContract.class)));
    }
    @Test void contextIsImmutableAndParticipatesInContractHash() throws Exception {
        var original = JSON.treeToValue(fixture("task"),CodingTaskContract.class);
        var node = tree(original);
        node.set("runtimeContext",JSON.valueToTree(Map.of("schemaVersion","runtime_os.coding.context.v1",
            "consistency","TASK_SNAPSHOT","model",Map.of("selectedModel","authorized-model"))));
        var updated = JSON.treeToValue(node,CodingTaskContract.class);
        assertThat(CodingCanonicalHash.sha256(updated)).isNotEqualTo(CodingCanonicalHash.sha256(original));
        assertThatThrownBy(() -> ((Map<String,Object>)updated.runtimeContext().get("model")).put("selectedModel","other"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void credentialAndArbitraryConfigurationCannotCrossTheContract() {
        for (String key : List.of("apiKey","password","baseUrl","authorization"))
            assertThatThrownBy(() -> CodingRuntimeContext.validate(Map.of("schemaVersion","runtime_os.coding.context.v1",
                "consistency","TASK_SNAPSHOT","model",Map.of(key,"private")))).isInstanceOf(CodingContractException.class);
    }
}
