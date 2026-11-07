package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.common.runtime.coding.NativeCodeWorkspace;
import java.nio.charset.StandardCharsets;
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
        for(String candidate:new String[]{command+" | tail -1",command+" 2>&1",command+" || true",command+"; true",
                "cd /tmp && "+command,"cd /workspace/code/../other && "+command,"cd $PROJECT && "+command,
                "cd /workspace/code && "+command+" && echo done","echo '"+command+"'"})
            assertThat(NativeCommandVerification.matches(command,hash(candidate))).as(candidate).isFalse();
        assertThat(NativeCommandVerification.hashes(null)).isEmpty();
    }
}
