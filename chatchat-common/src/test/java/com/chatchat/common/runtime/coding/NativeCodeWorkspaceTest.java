package com.chatchat.common.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NativeCodeWorkspaceTest {
    final ObjectMapper json=new ObjectMapper();
    final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
    @Test void nodeGeneratedUnicodeGoldenMatchesJavaAndBindsScope()throws Exception {
        var vector=json.readTree(getClass().getResourceAsStream("/runtime/coding/native-code-vector.json"));
        Map<String,String> files=json.convertValue(vector.get("files"),new TypeReference<>(){});
        Map<String,Object> golden=json.convertValue(vector.get("manifest"),new TypeReference<>(){});
        assertThat(CodingCanonicalHash.canonicalValueJson(NativeCodeWorkspace.manifest(scope,"native-task",files,0,null)))
            .isEqualTo(CodingCanonicalHash.canonicalValueJson(golden));
        assertThat(NativeCodeWorkspace.verify(scope,"native-task",golden,files).get("sha256")).isEqualTo(golden.get("sha256"));
        var other=new KernelDataScope("other","owner",null,null,"run",null,Map.of());
        assertThatThrownBy(()->NativeCodeWorkspace.verify(other,"native-task",golden,files)).isInstanceOf(SecurityException.class);
        var changed=new HashMap<>(files);changed.put("src/calc.py","print('altered')\n");
        assertThatThrownBy(()->NativeCodeWorkspace.verify(scope,"native-task",golden,changed)).isInstanceOf(SecurityException.class);
        var altered=new LinkedHashMap<>(golden);altered.put("taskId","other");
        assertThatThrownBy(()->NativeCodeWorkspace.verify(scope,"native-task",altered,files)).isInstanceOf(SecurityException.class);
    }
    @Test void deniesNonPortableOrEscapingPathsAndCaseCollisions(){
        for(String path:List.of("../secret","/tmp/x","a/../b","a\\b","C:x","a\nb",".git/config",".GIT/config","x.","CON.py","aux","a//b"))
            assertThatThrownBy(()->NativeCodeWorkspace.path(path)).as(path).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->NativeCodeWorkspace.files(Map.of("src/a.py","x","src/A.py","y"))).hasMessageContaining("Case-colliding");
        assertThatThrownBy(()->NativeCodeWorkspace.files(Map.of("a.py","\uD800"))).hasMessageContaining("UTF-8");
        assertThatThrownBy(()->NativeCodeWorkspace.files(Map.of("a.py","\u0000"))).hasMessageContaining("UTF-8");
    }
    @Test void boundedLineageAndEmptyProjectHaveDeterministicHashes(){
        var empty=NativeCodeWorkspace.manifest(scope,"task",Map.of(),0,null);
        assertThat(empty.get("files")).isEqualTo(List.of());
        var first=NativeCodeWorkspace.manifest(scope,"task",Map.of("src/main.py","print(1)\n"),1,(String)empty.get("sha256"));
        assertThat(first.get("parent")).isEqualTo(empty.get("sha256"));
        assertThatThrownBy(()->NativeCodeWorkspace.manifest(scope,"task",Map.of(),-1,null)).hasMessageContaining("lineage");
        assertThatThrownBy(()->NativeCodeWorkspace.manifest(scope,"task",Map.of(),1,null)).hasMessageContaining("lineage");
        assertThatThrownBy(()->NativeCodeWorkspace.manifest(scope,"task",Map.of(),65,(String)empty.get("sha256"))).hasMessageContaining("lineage");
        assertThatThrownBy(()->NativeCodeWorkspace.files(Map.of("x.py","x".repeat(256001)))).hasMessageContaining("bytes");
    }
}
