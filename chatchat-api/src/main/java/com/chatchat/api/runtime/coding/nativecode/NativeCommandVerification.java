package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.common.runtime.coding.NativeCodeWorkspace;
import com.chatchat.common.runtime.coding.CodingCanonicalHash;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Finite verification identities under the executor-owned cwd; does not parse or rewrite shell commands. */
final class NativeCommandVerification {
    private NativeCommandVerification(){}
    static List<String> hashes(String declared){
        if(declared==null||declared.isBlank())return List.of();
        return List.of(declared,"cd /workspace/code && "+declared,"cd '/workspace/code' && "+declared,"cd \"/workspace/code\" && "+declared)
            .stream().flatMap(command->Stream.of(command,command+" 2>&1"))
            .flatMap(command->Stream.of(NativeCodeWorkspace.sha(command.getBytes(StandardCharsets.UTF_8)),
                CodingCanonicalHash.sha256Value(Map.of("command",command)))).toList();
    }
    static boolean matches(String declared,String actualHash){return hashes(declared).contains(actualHash);}
}
