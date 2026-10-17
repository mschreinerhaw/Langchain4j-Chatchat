package com.chatchat.api.runtime;

import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.execution.LocalWorkflowRuntime;
import com.chatchat.common.runtime.workflow.WorkflowStartRequest;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Real file-backed H2: cancellation must not close a channel shared by other tasks. */
class RuntimeCancellationStorageTest {
    @TempDir Path directory;

    @Test void cancellationDuringPersistenceKeepsSharedStoreAndTerminalEventReadable() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("runtime").toAbsolutePath().toString().replace('\\','/')
            + ";DB_CLOSE_DELAY=-1;CACHE_SIZE=1";
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new AtomicBoolean();
        try (var owner = DriverManager.getConnection(url); var observer = DriverManager.getConnection(url)) {
            owner.createStatement().execute("CREATE TABLE runtime_event(id INT PRIMARY KEY, state VARCHAR(32), payload CLOB)");
            try (var insert = owner.prepareStatement("INSERT INTO runtime_event VALUES(1, 'NEIGHBOR_RUNNING', ?)")) {
                insert.setString(1, "observed input record\n".repeat(150000));
                insert.executeUpdate();
            }
            owner.createStatement().execute("CHECKPOINT SYNC");
            var runtime = new LocalWorkflowRuntime(executor, new AgentRuntimeProperties());
            runtime.register("test", String.class, String.class, (input, context) -> {
                entered.countDown();
                while (!release.get()) Thread.onSpinWait();
                try {
                    context.checkCancellation();
                    return input;
                } finally {
                    // Deterministically exercise MVStore file I/O at cancellation cleanup.
                    try (var rows = owner.createStatement().executeQuery("SELECT payload FROM runtime_event WHERE id=1")) {
                        rows.next();
                        try (var reader = rows.getCharacterStream(1)) { reader.transferTo(java.io.Writer.nullWriter()); }
                    }
                    assertThat(Thread.currentThread().isInterrupted()).as("Cancellation must not interrupt shared-store I/O").isFalse();
                    owner.createStatement().execute("INSERT INTO runtime_event(id,state) VALUES(2, 'CANCELLED')");
                    owner.createStatement().execute("CHECKPOINT SYNC");
                }
            });
            var handle = runtime.start(new WorkflowStartRequest<>("cancel-during-store", "test", "tenant", "key", "input"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.cancel("cancel-during-store", "owner requested stop")).isTrue();
            release.set(true);
            assertThatThrownBy(() -> handle.completion().get(10, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
            observer.createStatement().execute("INSERT INTO runtime_event(id,state) VALUES(3, 'NEIGHBOR_COMPLETED')");
            try (var rows = observer.createStatement().executeQuery("SELECT COUNT(*) FROM runtime_event")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(3);
            }
        } finally {
            release.set(true);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            try (var connection = DriverManager.getConnection(url)) { connection.createStatement().execute("SHUTDOWN"); }
        }
    }
}
