package com.chatchat.mcpserver.datacapability.connection;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeClientPoolTest {
    @Test void rotationKeepsInflightClientAliveUntilItsLastLeaseCloses() throws Exception {
        var first = mock(AutoCloseable.class); var second = mock(AutoCloseable.class);
        try (var pool = new NativeClientPool<AutoCloseable>(2)) {
            var a = pool.acquire("asset", "old-credentials", () -> first);
            var b = pool.acquire("asset", "old-credentials", () -> { throw new AssertionError("must reuse"); });
            var c = pool.acquire("asset", "new-credentials", () -> second);
            assertThat(c.client()).isSameAs(second);
            a.close(); verify(first, never()).close();
            b.close(); b.close(); verify(first, times(1)).close();
            c.close(); verify(second, never()).close();
        }
        verify(second, times(1)).close();
    }
    @Test void capacityIncludesRetiredInflightClientsAndRejectsWithoutCreatingMore() throws Exception {
        var first = mock(AutoCloseable.class);
        var pool = new NativeClientPool<AutoCloseable>(1);
        var lease = pool.acquire("asset", "one", () -> first);
        assertThatThrownBy(() -> pool.acquire("asset", "two", () -> { throw new AssertionError("must not create"); }))
            .hasMessageContaining("capacity");
        pool.close(); verify(first, never()).close();
        lease.close(); verify(first).close();
    }
    @Test void evictsIdleClientsAndShutdownRejectsNewAcquisitions() throws Exception {
        var first = mock(AutoCloseable.class); var second = mock(AutoCloseable.class);
        var pool = new NativeClientPool<AutoCloseable>(1);
        pool.acquire("a", "one", () -> first).close();
        var lease = pool.acquire("b", "two", () -> second);
        verify(first).close();
        pool.close(); verify(second, never()).close();
        assertThatThrownBy(() -> pool.acquire("c", "three", () -> first)).hasMessageContaining("shutting down");
        lease.close(); pool.close(); verify(second, times(1)).close();
    }
}
