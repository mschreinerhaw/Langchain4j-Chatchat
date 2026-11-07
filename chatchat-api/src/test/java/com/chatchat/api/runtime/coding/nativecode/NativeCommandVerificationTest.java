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
}
