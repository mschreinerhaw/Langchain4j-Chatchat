package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.common.runtime.coding.NativeCodeWorkspace;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Finite verification identities under the executor-owned cwd; does not parse or rewrite shell commands. */
final class NativeCommandVerification {
    private NativeCommandVerification(){}
    static List<String> hashes(String declared){
        if(declared==null||declared.isBlank())return List.of();
        return List.of(declared,"cd /workspace/code && "+declared,"cd '/workspace/code' && "+declared,"cd \"/workspace/code\" && "+declared)
            .stream().map(command->NativeCodeWorkspace.sha(command.getBytes(StandardCharsets.UTF_8))).toList();
    }
    static boolean matches(String declared,String actualHash){return hashes(declared).contains(actualHash);}
}
