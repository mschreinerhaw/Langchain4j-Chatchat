package com.chatchat.api.runtime;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.dataset.DatasetHandle;
import com.chatchat.agents.orchestration.analysis.dataset.PagedDatasetHandle;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingChunkedSnapshotTest {
    @Test void streamsImmutableChunksRetriesAndResumesWithoutChangingManifest() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:coding-chunks-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(source);
        var json=new ObjectMapper().findAndRegisterModules();
        var evidence=new JdbcRuntimeEvidenceStore(new JdbcTemplate(source),json,new DataSourceTransactionManager(source));
        var python=mock(McpPythonControlPlanePort.class);
        var stored=new HashMap<String,byte[]>();var attempts=new AtomicInteger();var cancelled=new AtomicBoolean();
        when(python.uploadDataFile(anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{
            if(attempts.getAndIncrement()==0)throw new IllegalStateException("transient upload failure");
            String id=call.getArgument(2), hash=call.getArgument(4);byte[] bytes=call.getArgument(5);
            if(stored.containsKey(id)&&!Arrays.equals(stored.get(id),bytes))throw new SecurityException("immutable chunk collision");
            stored.put(id,bytes);return new McpPythonControlPlanePort.DataFileResult(id,"stored","mounted",bytes.length,hash,"AVAILABLE");
        });
        var access=new CodingInvocationContext();var snapshots=new CodingInputSnapshotService(evidence,python,access,json);
        var scope=new KernelDataScope("tenant","user",null,null,"run",null,Map.of());
        int count=18_000;
        DatasetHandle handle=new PagedDatasetHandle(count,true,(offset,limit)->{
            var rows=new ArrayList<Map<String,Object>>();
            for(long index=offset;index<Math.min(count,offset+limit);index++)
                rows.add(Map.of("id",index,"payload","x".repeat(1024)));
            return new DatasetHandle.Page(offset,rows,offset+rows.size()<count);
        },null,()->{},Map.of());
        var delivered=new Dataset("source",Map.of(),handle);
        long firstStarted=System.nanoTime();
        var first=access.as(scope,EnumSet.of(CodingTaskAccess.Action.SUBMIT),()->
            snapshots.captureChunked(scope,"task",List.of(delivered),"source","decision","workspace",
                20_000,32L*1024*1024,()->{}));
        long firstMillis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-firstStarted);
        assertThat(first.chunked()).isTrue();assertThat(first.chunks()).hasSize(5);
        assertThat(attempts.get()).isGreaterThanOrEqualTo(7); // one transient retry, five chunks, manifest
        long total=first.chunks().stream().mapToLong(CodingInputSnapshotService.Chunk::size).sum();
        assertThat(total).isGreaterThan(16L*1024*1024).isLessThan(32L*1024*1024);
        var manifest=json.readTree(stored.get(first.fileId()));
        assertThat(manifest.path("chunks").size()).isEqualTo(5);
        assertThat(manifest.path("rowCount").asLong()).isEqualTo(count);
        assertThat(first.chunks().stream().mapToLong(CodingInputSnapshotService.Chunk::rowCount).sum()).isEqualTo(count);
        long resumeStarted=System.nanoTime();
        var again=access.as(scope,EnumSet.of(CodingTaskAccess.Action.SUBMIT),()->
            snapshots.captureChunked(scope,"task",List.of(delivered),"source","decision","workspace",
                20_000,32L*1024*1024,()->{}));
        long resumeMillis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-resumeStarted);
        System.out.printf("CAPACITY_BENCHMARK rows=%d bytes=%d chunks=%d captureMs=%d resumeMs=%d%n",
            count,total,first.chunks().size(),firstMillis,resumeMillis);
        assertThat(again).isEqualTo(first);
        assertThat(stored).hasSize(6);
        assertThat(access.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->snapshots.describe(scope,"task",first)))
            .containsKey("chunks").doesNotContainKeys("records","rows");
        assertThatThrownBy(()->access.as(scope,EnumSet.of(CodingTaskAccess.Action.SUBMIT),()->
            snapshots.captureChunked(scope,"other",List.of(delivered),"source","decision","workspace",
                20_000,32L*1024*1024,()->{cancelled.set(true);throw new CancellationException();})))
            .isInstanceOf(CancellationException.class);
        assertThat(cancelled).isTrue();
    }
}
