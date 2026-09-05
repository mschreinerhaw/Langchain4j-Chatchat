package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimAcceptanceGraphTest {
    @Test void cancellationDoesNotBecomePartialPublication() {
        assertThatThrownBy(() -> new ClaimAcceptanceGraph().execute(new ClaimAcceptanceGraph.Work<String>() {
            public void build() {}
            public void validate() {}
            public void review() { throw new CancellationException("cancelled"); }
            public boolean needsRepair() { return true; }
            public void repair() { throw new AssertionError("Must not repair cancelled work"); }
            public void revalidate() { throw new AssertionError("Must not revalidate cancelled work"); }
            public String assemble() { throw new AssertionError("Must not publish cancelled work"); }
        })).isInstanceOf(CancellationException.class);
    }
}
