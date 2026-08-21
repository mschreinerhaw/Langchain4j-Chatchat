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

/** Asset/workspace 持久化，Docker 容器只在单次执行期间存在。 */
@Service @RequiredArgsConstructor
public class PythonDockerRuntime {
    private final PythonRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final PythonDataFileService dataFiles;

    public ProvisionResult provision(PythonEnvironment environment,String tenantId,String ownerId,String assetId){
        try{
            validateEnvironment(environment);
            Path workspace=workspace(tenantId,ownerId,assetId);
            Files.createDirectories(workspace.resolve("scripts"));Files.createDirectories(workspace.resolve("output"));
            ExecResult inspect=run(List.of(properties.getDockerCommand(),"image","inspect",environment.getDockerImage()),Duration.ofSeconds(30));
            if(inspect.exitCode()!=0)return new ProvisionResult(false,"",workspace.toString(),"MCP 节点不存在受控 Runtime 镜像："+environment.getDockerImage());
            return new ProvisionResult(true,"",workspace.toString(),"Asset workspace 已就绪；执行时创建临时容器");
        }catch(Exception ex){return new ProvisionResult(false,"","",ex.getMessage());}
    }

    public ExecResult execute(PythonEnvironment env,String tenant,String owner,String asset,String fileName,String source,Map<String,Object> parameters){
        if(fileName==null||!fileName.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,170}\\.py"))throw new IllegalArgumentException("非法 Python 文件名");
        validateEnvironment(env);
        String container="chatchat-py-"+safe(asset).toLowerCase(Locale.ROOT)+"-"+UUID.randomUUID().toString().substring(0,8);
        try{
            ProvisionResult ready=provision(env,tenant,owner,asset);
            if(!ready.ready())return new ExecResult(container,-1,"",ready.message(),0,false);
            Path assetWorkspace=workspace(tenant,owner,asset);Path scripts=assetWorkspace.resolve("scripts");Path inputRoot=dataFiles.uploads(tenant,owner);
            Path script=scripts.resolve(fileName).normalize();
            if(!script.startsWith(scripts))throw new IllegalArgumentException("非法脚本路径");
            Files.writeString(script,source,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
            String input=objectMapper.writeValueAsString(parameters==null?Map.of():parameters);
            List<String> command=new ArrayList<>(List.of(properties.getDockerCommand(),"run","--rm","--name",container,
                "--label","chatchat.python.asset="+asset,"--label","chatchat.python.environment="+env.getId(),
                "--cpus",env.getCpuLimit(),"--memory",env.getMemoryLimit(),"--pids-limit","256",
                "--read-only","--cap-drop","ALL","--security-opt","no-new-privileges:true",
                "--user",env.getRuntimeUser(),"--tmpfs","/tmp:rw,noexec,nosuid,size="+env.getTmpfsLimit(),
                "--tmpfs","/workspace/output:rw,noexec,nosuid,size="+env.getDiskLimit(),
                "-v",assetWorkspace.toAbsolutePath()+":/workspace:ro","-v",inputRoot.toAbsolutePath()+":/data/input:ro",
                "-w","/workspace","-e","CHATCHAT_INPUT_JSON="+input));
            if("NONE".equals(env.getNetworkPolicy()))command.addAll(List.of("--network","none"));
            else command.addAll(List.of("--network",env.getNetworkName()));
            command.addAll(List.of(env.getDockerImage(),"python","/workspace/scripts/"+fileName));
            ExecResult result=run(command,Duration.ofSeconds(env.getTimeoutSeconds()));
            return new ExecResult(container,result.exitCode(),result.stdout(),result.stderr(),result.durationMs(),result.timedOut());
        }catch(Exception ex){return new ExecResult(container,-1,"",ex.getMessage(),0,false);}
        finally{try{run(List.of(properties.getDockerCommand(),"rm","-f",container),Duration.ofSeconds(15));}catch(Exception ignored){}}
    }

    private void validateEnvironment(PythonEnvironment env){
        String image=required(env.getDockerImage(),"Docker 镜像");
        if(!image.matches("[A-Za-z0-9][A-Za-z0-9._/:@-]{1,298}"))throw new IllegalArgumentException("Runtime 镜像名称不合法");
        if(image.endsWith(":latest")||(!image.contains(":")&&!image.contains("@sha256:")))throw new IllegalArgumentException("Runtime 镜像必须固定版本，禁止 latest 或无标签镜像");
        if(!required(env.getRuntimeUser(),"运行用户").matches("[0-9]{1,10}(:[0-9]{1,10})?"))throw new IllegalArgumentException("运行用户必须是非 root UID[:GID]");
        if(env.getRuntimeUser().startsWith("0")&&(env.getRuntimeUser().equals("0")||env.getRuntimeUser().startsWith("0:")))throw new IllegalArgumentException("禁止 root 用户运行 Python");
        if(!Set.of("NONE","NAMED").contains(env.getNetworkPolicy()))throw new IllegalArgumentException("网络策略只能是 NONE 或 NAMED");
        if(env.getTimeoutSeconds()<1||env.getTimeoutSeconds()>3600)throw new IllegalArgumentException("执行超时必须在 1 到 3600 秒之间");
        if("NAMED".equals(env.getNetworkPolicy())&&(env.getNetworkName()==null||!env.getNetworkName().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,126}")))throw new IllegalArgumentException("NAMED 网络必须指定平台预建 Docker 网络");
        if(env.getCpuLimit()==null||!env.getCpuLimit().matches("[0-9]+(\\.[0-9]+)?"))throw new IllegalArgumentException("CPU 限制格式不合法");size(env.getMemoryLimit(),"内存");size(env.getDiskLimit(),"临时磁盘");size(env.getTmpfsLimit(),"tmpfs");
    }
    private void size(String value,String label){if(value==null||!value.matches("[1-9][0-9]*(k|m|g)"))throw new IllegalArgumentException(label+"限制格式不合法");}
    private String required(String value,String label){if(value==null||value.isBlank())throw new IllegalArgumentException(label+"不能为空");return value.trim();}
    private Path workspace(String tenant,String owner,String asset)throws IOException{Path root=Paths.get(properties.getWorkspaceRoot()).toAbsolutePath().normalize();Files.createDirectories(root);Path path=root.resolve(safe(tenant)).resolve(safe(owner)).resolve(safe(asset)).normalize();if(!path.startsWith(root))throw new IllegalArgumentException("非法 workspace");return path;}
    private String safe(String value){return value==null?"default":value.replaceAll("[^A-Za-z0-9_.-]","_");}
    private ExecResult run(List<String> command,Duration timeout)throws IOException,InterruptedException{long started=System.nanoTime();Process process=new ProcessBuilder(command).start();ExecutorService readers=Executors.newFixedThreadPool(2);try{Future<String> stdout=readers.submit(()->read(process.getInputStream()));Future<String> stderr=readers.submit(()->read(process.getErrorStream()));boolean finished=process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS);if(!finished){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}return new ExecResult("",finished?process.exitValue():124,get(stdout),get(stderr),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),!finished);}finally{readers.shutdownNow();}}
    private String read(InputStream stream)throws IOException{int limit=properties.getOutputLimitBytes();ByteArrayOutputStream kept=new ByteArrayOutputStream(Math.min(limit,8192));byte[] buffer=new byte[8192];int total=0,read;while((read=stream.read(buffer))>=0){if(total<limit){int copy=Math.min(read,limit-total);kept.write(buffer,0,copy);}total+=read;}String value=kept.toString(StandardCharsets.UTF_8);return total>limit?value+"\n[输出已截断]":value;}
    private String get(Future<String> f){try{return f.get(5,TimeUnit.SECONDS);}catch(Exception ex){return ex.getMessage();}}
    public record ProvisionResult(boolean ready,String containerName,String workspacePath,String message){}
    public record ExecResult(String containerId,int exitCode,String stdout,String stderr,long durationMs,boolean timedOut){}
}
