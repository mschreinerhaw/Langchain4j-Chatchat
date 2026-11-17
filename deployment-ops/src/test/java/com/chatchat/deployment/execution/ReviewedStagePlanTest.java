package com.chatchat.deployment.execution;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReviewedStagePlanTest {
    private ObjectNode manifest()throws Exception{return (ObjectNode)StateFiles.JSON.readTree("""
        {"images":{"api":{"id":"sha256:reviewed"}},"components":[{"id":"api","image":"api","entrypoint":["java"],"command":["-jar","app.jar"]}],"runtimeInstaller":{"services":["api"]}}
        """);}
    private ObjectNode plan()throws Exception{return (ObjectNode)StateFiles.JSON.readTree("""
        {"format":"runtime-os-stage-plan/v1","stageRoot":"/opt/runtime-os-docker-stage","compose":{"services":{"api":{"image":"sha256:reviewed","entrypoint":["java"],"command":["-jar","app.jar"],"environment":{"EXISTING_PASSWORD":"private-only"}}}}}
        """);}
    @Test void acceptsExactPrivateImageAndStartupInventory()throws Exception{
        assertDoesNotThrow(()->ReviewedStagePlan.validate(plan(),manifest()));
    }
    @Test void rejectsChangedImageOrCommandBeforeDockerChanges()throws Exception{
        ObjectNode plan=plan();((ObjectNode)plan.path("compose").path("services").path("api")).put("image","sha256:other");
        assertThrows(IllegalArgumentException.class,()->ReviewedStagePlan.validate(plan,manifest()));
        ObjectNode changed=plan();((ObjectNode)changed.path("compose").path("services").path("api")).putArray("command").add("unreviewed-command");
        assertThrows(IllegalArgumentException.class,()->ReviewedStagePlan.validate(changed,manifest()));
    }
    @Test void rejectsDifferentRootOrAnUnresolvedHostConfiguration()throws Exception{
        ObjectNode plan=plan();plan.put("stageRoot","/other-customer");assertThrows(IllegalArgumentException.class,()->ReviewedStagePlan.validate(plan,manifest()));
        ObjectNode changed=plan();((ObjectNode)changed.path("compose").path("services").path("api")).put("container_name","unrelated-production");
        assertThrows(IllegalArgumentException.class,()->ReviewedStagePlan.validate(changed,manifest()));
    }
    @Test void rejectsPublicMountSummaryThatHidesPrivateBindings()throws Exception{
        ObjectNode manifest=manifest();((ObjectNode)manifest.path("runtimeInstaller")).putObject("stagePlan").put("sourceRoot","/opt/runtime-os-docker-stage").putArray("mounts");
        ObjectNode plan=plan();((ObjectNode)plan.path("compose").path("services").path("api")).putArray("volumes").addObject().put("source","/opt/runtime-os-docker-stage/api/data").put("target","/srv/app/data");
        assertThrows(IllegalArgumentException.class,()->ReviewedStagePlan.validate(plan,manifest));
    }
}
