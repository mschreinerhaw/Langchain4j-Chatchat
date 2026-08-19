package com.chatchat.mcpserver.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Service @RequiredArgsConstructor
public class PythonDockerRuntime {
    private final PythonRuntimeProperties properties;
    private final ObjectMapper objectMapper;

    public ProvisionResult provision(PythonEnvironment environment,String tenantId,String ownerId,String assetId){
        try{
            Path workspace=workspace(tenantId,ownerId,assetId);Files.createDirectories(workspace.resolve("scripts"));String container=container(assetId);
            ExecResult inspect=run(List.of(properties.getDockerCommand(),"inspect",container),Duration.ofSeconds(15));
            if(inspect.exitCode()!=0){List<String> command=new ArrayList<>(List.of(properties.getDockerCommand(),"create","--name",container,"--label","chatchat.python.asset="+assetId,"--label","chatchat.python.environment="+environment.getId(),"--cpus",environment.getCpuLimit(),"--memory",environment.getMemoryLimit(),"--pids-limit","256","--cap-drop","ALL","--security-opt","no-new-privileges:true"));if(!environment.isNetworkEnabled())command.addAll(List.of("--network","none"));command.addAll(List.of("-v",workspace+":/workspace","-w","/workspace",environment.getDockerImage(),"sh","-c","while true; do sleep 3600; done"));ExecResult created=run(command,Duration.ofMinutes(5));if(created.exitCode()!=0)return new ProvisionResult(false,container,workspace.toString(),created.stderr());}
            ExecResult start=run(List.of(properties.getDockerCommand(),"start",container),Duration.ofSeconds(30));return new ProvisionResult(start.exitCode()==0,container,workspace.toString(),start.exitCode()==0?"":start.stderr());
        }catch(Exception ex){return new ProvisionResult(false,container(assetId),"",ex.getMessage());}
    }

    public ExecResult execute(PythonEnvironment env,String tenant,String owner,String asset,String fileName,String source,Map<String,Object> parameters){
        if(fileName==null||!fileName.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,170}\\.py"))throw new IllegalArgumentException("非法 Python 文件名");
        try{ProvisionResult ready=provision(env,tenant,owner,asset);if(!ready.ready())return new ExecResult(-1,"",ready.message(),0,false);Path script=workspace(tenant,owner,asset).resolve("scripts").resolve(fileName).normalize();if(!script.startsWith(workspace(tenant,owner,asset).resolve("scripts")))throw new IllegalArgumentException("非法脚本路径");Files.writeString(script,source,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);String input=objectMapper.writeValueAsString(parameters==null?Map.of():parameters);return run(List.of(properties.getDockerCommand(),"exec","-e","CHATCHAT_INPUT_JSON="+input,ready.containerName(),"python","/workspace/scripts/"+fileName),Duration.ofSeconds(env.getTimeoutSeconds()));}catch(Exception ex){return new ExecResult(-1,"",ex.getMessage(),0,false);}
    }
    private Path workspace(String tenant,String owner,String asset)throws IOException{Path root=Paths.get(properties.getWorkspaceRoot()).toAbsolutePath().normalize();Files.createDirectories(root);Path path=root.resolve(safe(tenant)).resolve(safe(owner)).resolve(safe(asset)).normalize();if(!path.startsWith(root))throw new IllegalArgumentException("非法 workspace");return path;}
    private String container(String asset){return "chatchat-mcp-py-"+safe(asset).toLowerCase(Locale.ROOT);}
    private String safe(String value){return value==null?"default":value.replaceAll("[^A-Za-z0-9_.-]","_");}
    private ExecResult run(List<String> command,Duration timeout)throws IOException,InterruptedException{long started=System.nanoTime();Process process=new ProcessBuilder(command).start();ExecutorService readers=Executors.newFixedThreadPool(2);try{Future<String> stdout=readers.submit(()->read(process.getInputStream()));Future<String> stderr=readers.submit(()->read(process.getErrorStream()));boolean finished=process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS);if(!finished){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}return new ExecResult(finished?process.exitValue():124,get(stdout),get(stderr),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),!finished);}finally{readers.shutdownNow();}}
    private String read(InputStream stream)throws IOException{byte[] bytes=stream.readNBytes(properties.getOutputLimitBytes()+1);String value=new String(bytes,0,Math.min(bytes.length,properties.getOutputLimitBytes()),StandardCharsets.UTF_8);return bytes.length>properties.getOutputLimitBytes()?value+"\n[输出已截断]":value;}
    private String get(Future<String> f){try{return f.get(5,TimeUnit.SECONDS);}catch(Exception ex){return ex.getMessage();}}
    public record ProvisionResult(boolean ready,String containerName,String workspacePath,String message){}
    public record ExecResult(int exitCode,String stdout,String stderr,long durationMs,boolean timedOut){}
}
