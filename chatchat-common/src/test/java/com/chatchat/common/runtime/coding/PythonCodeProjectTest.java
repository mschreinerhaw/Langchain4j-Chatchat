package com.chatchat.common.runtime.coding;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PythonCodeProjectTest {
    final ObjectMapper json=new ObjectMapper();
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new RuntimeException(e);}}
    @Test void preservesExactBytesAndDirectoryRelationsWithoutChangingTheSource() throws Exception {
        var project=new PythonCodeProject.Project("pkg/main.py",List.of(new PythonCodeProject.File("pkg/main.py","from .calc import total\r\nprint(total())\r\n"),new PythonCodeProject.File("pkg/__init__.py",""),new PythonCodeProject.File("pkg/calc.py","def total(): return 7\n")));
        var config=PythonCodeProject.context(project,this::encode);String payload=PythonCodeProject.fromContext(config);
        assertThat(json.readValue(payload,PythonCodeProject.Project.class)).isEqualTo(PythonCodeProject.validate(project));
        String wrapper=PythonCodeProject.template().replace(PythonCodeProject.TOKEN,Base64.getEncoder().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(PythonCodeProject.decode(wrapper)).contains(payload);
        assertThatThrownBy(()->PythonCodeProject.decode(wrapper+"print('unsafe')\n")).isInstanceOf(SecurityException.class);
    }
    @Test void rejectsTraversalAbsolutePathsAndCaseCollisions(){
        for(String path:List.of("../x.py","/x.py","a/../x.py","a\\x.py","a//x.py","C:x.py"))assertThatThrownBy(()->PythonCodeProject.path(path)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PythonCodeProject.validate(new PythonCodeProject.Project("a.py",List.of(new PythonCodeProject.File("a.py",""),new PythonCodeProject.File("A.py",""))))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUnboundOrAlteredSourceSnapshots(){
        var config=new LinkedHashMap<>(PythonCodeProject.context(new PythonCodeProject.Project("main.py",List.of(new PythonCodeProject.File("main.py","pass"))),this::encode));
        config.put("sha256","0".repeat(64));assertThatThrownBy(()->PythonCodeProject.fromContext(config)).isInstanceOf(SecurityException.class);
        config.put("adapterTemplate","print('unsafe')");assertThatThrownBy(()->PythonCodeProject.fromContext(config)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void boundsSourceCountSizeAndEntry(){
        assertThatThrownBy(()->PythonCodeProject.validate(new PythonCodeProject.Project("missing.py",List.of(new PythonCodeProject.File("main.py","pass"))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PythonCodeProject.validate(new PythonCodeProject.Project("main.py",List.of(new PythonCodeProject.File("main.py","x".repeat(128001)))))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void diagnosticFramesAcceptOnlyNormalizedProjectPaths(){
        assertThat(new ExecutionFeedback.Frame("project/pkg/main.py",3,null).file()).isEqualTo("project/pkg/main.py");
        for(String value:List.of("project/../secret.py","project//main.py","/tmp/main.py","project/C:main.py","project/pkg\\main.py"))
            assertThatThrownBy(()->new ExecutionFeedback.Frame(value,1,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void diagnosticWireSchemaAcceptsProjectFramesAndRejectsTraversal()throws Exception{
        try(var input=getClass().getResourceAsStream("/runtime/coding/execution-feedback.schema.json")) {
            var schema=com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(json.readTree(input).path("$defs").path("failure").path("properties").path("stack"));
            assertThat(schema.validate(json.valueToTree(List.of(new ExecutionFeedback.Frame("project/pkg/main.py",3,null))))).isEmpty();
            assertThat(schema.validate(json.readTree("[{\"file\":\"project/../private.py\",\"line\":3,\"column\":null}]"))).isNotEmpty();
        }
    }
    @Test void historicalAdapterIsReadableButCannotReplaceTheCurrentTaskAdapter() {
        var project=new PythonCodeProject.Project("main.py",List.of(new PythonCodeProject.File("main.py","pass")));
        var context=PythonCodeProject.context(project,this::encode);String payload=PythonCodeProject.fromContext(context);
        String source=PythonCodeProject.template().replace(PythonCodeProject.TOKEN,Base64.getEncoder().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String historical=source.replace("__root = Path('/tmp/runtime-python-project')","__root = Path('/workspace/output/validation/project')");
        assertThat(PythonCodeProject.decode(historical)).contains(payload);
        assertThat(PythonCodeProject.decode(source,context)).contains(payload);
        assertThatThrownBy(()->PythonCodeProject.decode(historical,context)).isInstanceOf(SecurityException.class);
    }
}
