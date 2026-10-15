package com.chatchat.common.runtime.evidence;

import com.chatchat.common.kernel.KernelDataScope;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class RuntimeCheckpointMutationTest {
    @Test void adaptersWithoutTransactionsCannotSilentlyEmulateAtomicMutationWithCas() {
        var unsupported=new RuntimeExecutionCheckpointPort(){
            @Override public Optional<String> readExecutionCheckpoint(KernelDataScope scope,String key){throw new AssertionError("No unlocked read");}
            @Override public boolean compareAndSetExecutionCheckpoint(KernelDataScope scope,String key,String expected,String next){throw new AssertionError("No CAS emulation");}
        };
        assertThatThrownBy(()->unsupported.mutateExecutionCheckpoint(null,"coding.tasks.v1",current->"new"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
