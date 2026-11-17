package com.chatchat.deployment.protocol;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class InstallationManifestTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode manifest()throws Exception {
        return (ObjectNode)json.readTree("""
            {"schemaVersion":1,"name":"qa","version":"1.0.0","platform":"linux/amd64",
             "images":{"redis":{"id":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","archive":"images/redis.tar","size":1024}},
             "parameters":{},"volumes":{},"components":[
               {"id":"api","image":"redis","dependsOn":["init"],"health":{"type":"tcp","port":6379}},
               {"id":"init","image":"redis","kind":"job","command":["/bin/true"]}]}
            """);
    }
    @Test void ordersDependenciesAndRejectsCycleBeforeMutation()throws Exception {
        ObjectNode manifest=manifest();assertEquals("init",InstallationManifest.validate(manifest,Set.of("images/redis.tar")).get(0).path("id").asText());
        ((ObjectNode)manifest.path("components").get(1)).set("dependsOn",json.createArrayNode().add("api"));
        assertThrows(IllegalArgumentException.class,()->InstallationManifest.validate(manifest,Set.of("images/redis.tar")));
    }
    @Test void passwordComesFromUserAndStaysAbsentFromPublicState()throws Exception {
        ObjectNode schema=json.createObjectNode();schema.set("password",json.createObjectNode().put("type","string").put("secret",true).put("required",true));
        assertThrows(IllegalArgumentException.class,()->InstallationManifest.parameters(schema,json.createObjectNode(),null));
        ObjectNode chosen=InstallationManifest.parameters(schema,json.createObjectNode().put("password","Chosen$\\Value!"),null);
        assertEquals("Chosen$\\Value!",InstallationManifest.render(json.readTree("[\"${password}\"]"),chosen).get(0).asText());
        assertFalse(InstallationManifest.publicParameters(schema,chosen).has("password"));
        assertEquals(chosen,InstallationManifest.parameters(schema,json.createObjectNode().put("password",""),chosen));
        ObjectNode manifest=manifest();manifest.set("parameters",schema);((ObjectNode)schema.path("password")).put("default","unsafe");
        assertThrows(IllegalArgumentException.class,()->InstallationManifest.validate(manifest,Set.of("images/redis.tar")));
    }
    @Test void rejectsUnsignedArchiveAndPrivilegedHostPaths()throws Exception {
        assertThrows(IllegalArgumentException.class,()->InstallationManifest.validate(manifest(),Set.of()));
        for(String name:new String[]{"../escape","/absolute","assets/../../escape","assets//file"})assertThrows(IllegalArgumentException.class,()->InstallationManifest.member(name));
        for(String path:new String[]{"/proc/1/root","/var/run/docker.sock","/data/../../etc","/"})assertThrows(IllegalArgumentException.class,()->InstallationManifest.containerPath(path));
    }
    @Test void distinguishesLiteralContainerVariablesFromUserParameters()throws Exception {
        var parameters=json.createObjectNode().put("password","$${must_stay_literal}").put("root","/data");
        assertEquals("${HOME} /data $${must_stay_literal}",InstallationManifest.render(json.getNodeFactory().textNode("$${HOME} ${root} ${password}"),parameters).asText());
        assertThrows(IllegalArgumentException.class,()->InstallationManifest.render(json.getNodeFactory().textNode("${unknown}"),parameters));
    }
}
