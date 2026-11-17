package com.chatchat.deployment.execution;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Host /proc samples and Docker cgroup counters, with truthful unavailable values. */
public final class HostMetrics {
    private long[] previousCpu;
    private long previousNanos,previousRx,previousTx;
    private final Path disk;
    public HostMetrics(Path disk){this.disk=disk;}
    public synchronized ObjectNode sample()throws IOException{
        ObjectNode result=StateFiles.JSON.createObjectNode().put("scope","host").put("collectedAt",StateFiles.now()).put("available",true);
        Map<String,Long> memory=new HashMap<>();for(String line:Files.readAllLines(Path.of("/proc/meminfo"))){String[] pair=line.split(":",2);memory.put(pair[0],Long.parseLong(pair[1].trim().split("\\s+")[0])*1024);}
        long total=memory.get("MemTotal"),available=memory.get("MemAvailable");FileStore store=Files.getFileStore(disk);long diskTotal=store.getTotalSpace(),diskAvailable=store.getUsableSpace();
        result.put("memoryTotalBytes",total).put("memoryUsedBytes",total-available).put("memoryPercent",100.0*(total-available)/total).put("diskTotalBytes",diskTotal).put("diskAvailableBytes",diskAvailable).put("diskPercent",100.0*(diskTotal-diskAvailable)/diskTotal).put("diskMount",disk.toString());
        String[] raw=Files.readAllLines(Path.of("/proc/stat")).get(0).trim().split("\\s+");long[] ticks=new long[Math.min(8,raw.length-1)];for(int i=0;i<ticks.length;i++)ticks[i]=Long.parseLong(raw[i+1]);
        result.putNull("cpuPercent");if(previousCpu!=null){long delta=Arrays.stream(ticks).sum()-Arrays.stream(previousCpu).sum(),idle=ticks[3]+ticks[4]-previousCpu[3]-previousCpu[4];if(delta>0)result.put("cpuPercent",Math.max(0,Math.min(100,100.0*(delta-idle)/delta)));}
        long rx=0,tx=0;boolean networkAvailable=Files.isReadable(Path.of("/proc/1/net/dev"));if(networkAvailable){for(String line:Files.readAllLines(Path.of("/proc/1/net/dev"))){if(!line.contains(":"))continue;String[] pair=line.split(":",2);if(pair[0].trim().equals("lo"))continue;String[] values=pair[1].trim().split("\\s+");rx+=Long.parseLong(values[0]);tx+=Long.parseLong(values[8]);}}
        long now=System.nanoTime();result.putNull("networkRxPerSecond").putNull("networkTxPerSecond");if(networkAvailable&&previousNanos!=0&&now>previousNanos){double seconds=(now-previousNanos)/1e9;result.put("networkRxPerSecond",Math.max(0,rx-previousRx)/seconds).put("networkTxPerSecond",Math.max(0,tx-previousTx)/seconds);}
        previousCpu=ticks;previousRx=rx;previousTx=tx;previousNanos=networkAvailable?now:0;return result;
    }
    public static ObjectNode container(JsonNode raw){
        JsonNode cpu=raw.path("cpu_stats"),previous=raw.path("precpu_stats"),memory=raw.path("memory_stats");long delta=cpu.path("cpu_usage").path("total_usage").asLong()-previous.path("cpu_usage").path("total_usage").asLong(),system=cpu.path("system_cpu_usage").asLong()-previous.path("system_cpu_usage").asLong();int cores=cpu.path("online_cpus").asInt(cpu.path("cpu_usage").path("percpu_usage").size());
        ObjectNode result=StateFiles.JSON.createObjectNode().put("scope","container");if(system>0&&cores>0&&delta>=0)result.put("cpuPercent",100.0*delta/system*cores);else result.putNull("cpuPercent");result.put("memoryBytes",Math.max(0,memory.path("usage").asLong()-memory.path("stats").path("inactive_file").asLong()));result.set("memoryLimitBytes",memory.path("limit").isMissingNode()?NullNode.instance:memory.path("limit"));
        long rx=0,tx=0,read=0,write=0;for(JsonNode network:raw.path("networks")){rx+=network.path("rx_bytes").asLong();tx+=network.path("tx_bytes").asLong();}for(JsonNode record:raw.path("blkio_stats").path("io_service_bytes_recursive")){if(record.path("op").asText().equalsIgnoreCase("read"))read+=record.path("value").asLong();if(record.path("op").asText().equalsIgnoreCase("write"))write+=record.path("value").asLong();}
        return result.put("networkRxBytes",rx).put("networkTxBytes",tx).put("blockReadBytes",read).put("blockWriteBytes",write);
    }
}
