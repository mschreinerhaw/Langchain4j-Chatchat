package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.common.runtime.coding.NativeCodeWorkspace;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.chatchat.common.runtime.coding.CodingCanonicalHash;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NativeCommandVerificationTest {
    final String command="python -m unittest discover -s tests -v";
    String hash(String value){return NativeCodeWorkspace.sha(value.getBytes(StandardCharsets.UTF_8));}
    @Test void onlyTheDeclaredCommandAndLiteralExecutorOwnedDirectoryAreEquivalent(){
        for(String candidate:new String[]{command,"cd /workspace/code && "+command,"cd '/workspace/code' && "+command,"cd \"/workspace/code\" && "+command})
            assertThat(NativeCommandVerification.matches(command,hash(candidate))).as(candidate).isTrue();
    }
    @Test void pipelinesAlternateDirectoriesAndAppendedSuccessCannotCertifyTheDeclaredCommand(){
        for(String candidate:new String[]{command+" | tail -1",command+" 2> result.txt",command+" || true",command+"; true",
                "cd /tmp && "+command,"cd /workspace/code/../other && "+command,"cd $PROJECT && "+command,
                "cd /workspace/code && "+command+" && echo done","echo '"+command+"'"})
            assertThat(NativeCommandVerification.matches(command,hash(candidate))).as(candidate).isFalse();
        assertThat(NativeCommandVerification.hashes(null)).isEmpty();
    }
    @Test void nativeObjectCommandHasItsOwnFiniteIdentityAndCannotSmuggleArgumentsOrEnvironment(){
        for(String candidate:new String[]{command,"cd /workspace/code && "+command,"cd '/workspace/code' && "+command,"cd \"/workspace/code\" && "+command}){
            assertThat(NativeCommandVerification.matches(command,CodingCanonicalHash.sha256Value(Map.of("command",candidate)))).isTrue();
            assertThat(NativeCommandVerification.matches(command,CodingCanonicalHash.sha256Value(Map.of("command",candidate,"args",java.util.List.of("--extra"))))).isFalse();
            assertThat(NativeCommandVerification.matches(command,CodingCanonicalHash.sha256Value(Map.of("command",candidate,"env",Map.of("PYTHONPATH","/tmp"))))).isFalse();
        }
        assertThat(NativeCommandVerification.matches(command,CodingCanonicalHash.sha256Value(Map.of("command",command+" || true")))).isFalse();
    }
    @Test void mergingStderrRetainsCommandExitIdentityWhilePipelinesAndFailureMaskingDoNot(){
        for(String prefix:new String[]{"","cd /workspace/code && ","cd '/workspace/code' && ","cd \"/workspace/code\" && "}){
            String merged=prefix+command+" 2>&1";
            assertThat(NativeCommandVerification.matches(command,hash(merged))).isTrue();
            assertThat(NativeCommandVerification.matches(command,CodingCanonicalHash.sha256Value(Map.of("command",merged)))).isTrue();
            assertThat(NativeCommandVerification.matches(command,hash(merged+" | tail -1"))).isFalse();
            assertThat(NativeCommandVerification.matches(command,hash(merged+" || true"))).isFalse();
        }
    }
    @Test void pipefailPolicyMatchesOnlyFrozenDeclarationAndBoundedOutputFilters(){
        for(String suffix:new String[]{""," 2>&1"," | tail -20"," 2>&1|tail -n 20"," | head -100","\t| head -n 1"})
            for(String prefix:new String[]{"","cd /workspace/code && ","cd '/workspace/code' && "}){
                assertThat(NativeCommandVerification.eligible(command,prefix+command+suffix)).as(prefix+suffix).isTrue();
                assertThat(NativeCommandVerification.eligible(command,Map.of("command",prefix+command+suffix))).isTrue();
            }
        assertThat(NativeCommandVerification.eligible(command,Map.of("command","python","args",java.util.List.of("-m","unittest","discover","-s","tests","-v")))).isTrue();
        assertThat(NativeCommandVerification.eligible(command,"cd /workspace/code && cat transform.py && "+command+" 2>&1 | tail -20")).isTrue();
        assertThat(NativeCommandVerification.eligible(command,"ls -la && "+command+" 2>&1 | head -100 && md5sum tests/test_main.py")).isTrue();
        for(String value:new String[]{command+" | tail -20 || true",command+" | tail -20 /tmp/fake",command+" | head -1; true",
                command+" | $(echo tail) -5",command+" > /tmp/fake",command+" | grep OK","cd /tmp && "+command,
                "set +o pipefail; "+command+" | tail -5",command+"\ntrue",command+" | tail -0",command+" | tail -99999",
                "exit 0 && "+command,"export PYTHONPATH=/tmp && "+command,"cat $(echo fake) && "+command,
                command+" && true",command+" && cat /tmp/a || true"})
            assertThat(NativeCommandVerification.eligible(command,value)).as(value).isFalse();
        assertThat(NativeCommandVerification.eligible(command,Map.of("command",command,"env",Map.of("PYTHONPATH","/tmp")))).isFalse();
        assertThat(NativeCommandVerification.eligible(command,Map.of("command","bash","args",java.util.List.of("-c",command)))).isFalse();
        // Legacy policy deliberately retains its old finite Hash semantics.
        assertThat(NativeCommandVerification.matches(command,hash(command+" | tail -20"))).isFalse();
    }
    @Test void commandIdentityRequiresHostPreparedEligibilityAndMatchingFrozenDeclaration(){
        var c=org.mockito.Mockito.mock(com.chatchat.common.runtime.coding.CodingTaskContract.class);
        var policy=com.chatchat.common.runtime.coding.NativeExecutorPolicy.PIPEFAIL;
        org.mockito.Mockito.when(c.metadataRefs()).thenReturn(java.util.List.of(policy.profile()));
        org.mockito.Mockito.when(c.runtimeContext()).thenReturn(Map.of("nativeExecutionPolicy",policy.id()));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();String sha=NativeCommandVerification.declarationHash(command);
        var prepared=json.valueToTree(Map.of("executorPolicy",policy.id(),"verificationEligible",true,"verificationDeclarationSha256",sha));
        var receipt=json.valueToTree(Map.of("verificationPolicy",policy.id(),"declaredCommandMatched",true,"declaredCommandSha256",sha));
        assertThat(NativeCommandVerification.certifies(c,command,prepared,receipt)).isTrue();
        ((com.fasterxml.jackson.databind.node.ObjectNode)prepared).put("verificationEligible",false);
        assertThat(NativeCommandVerification.certifies(c,command,prepared,receipt)).isFalse();
        ((com.fasterxml.jackson.databind.node.ObjectNode)prepared).put("verificationEligible",true);
        ((com.fasterxml.jackson.databind.node.ObjectNode)receipt).put("declaredCommandSha256","0".repeat(64));
        assertThat(NativeCommandVerification.certifies(c,command,prepared,receipt)).isFalse();
    }
}
