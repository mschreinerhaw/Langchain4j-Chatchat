package com.chatchat.deployment.execution;

import com.chatchat.deployment.docker.*;
import com.chatchat.deployment.protocol.*;
import com.chatchat.deployment.storage.StorageGuard;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import static com.chatchat.deployment.execution.ExecutionJson.*;
import static com.chatchat.deployment.protocol.InstallationManifest.require;

/** In-process Java node API. No CLI, interpreter or business lifecycle on shutdown. */
public final class JdkExecutor implements AutoCloseable {
    private final HttpServer server;private final ExecutorService requests;
    private final DockerClient docker;private final ExecutionJournal journal;
    private final SignedPackageStore packages;private final ResourceManager resources;private final DeploymentService deployments;
    private final Path root;private final String token;private final ReentrantLock mutations=new ReentrantLock(true);
    private final boolean readOnly=System.getenv().getOrDefault("OPS_READ_ONLY","0").equals("1");
    public JdkExecutor(Path root,Path trust,Properties config,int port)throws Exception{
        this.root=root;token=config.getProperty("EXECUTOR_TOKEN");Files.createDirectories(root);
        docker=new DockerClient(Path.of(System.getenv().getOrDefault("DOCKER_SOCKET","/var/run/docker.sock")));
        journal=new ExecutionJournal(root.resolve("journal"),config.getProperty("OPS_DB_PASSWORD"));
        packages=new SignedPackageStore(root.resolve("packages"),trust,docker,mutations);
        resources=new ResourceManager(docker,journal,config.getProperty("OPS_INSTANCE_ID"),root.resolve("apps"),mutations,System.getenv().getOrDefault("OPS_READ_ONLY","0").equals("1"));
        deployments=new DeploymentService(docker,packages,journal,config.getProperty("OPS_INSTANCE_ID"),root.resolve("apps"),mutations);resources.installationBusy(()->deployments.busy()||packages.busy());
        requests=Executors.newFixedThreadPool(12,r->{Thread t=new Thread(r,"jdk-node-http");t.setDaemon(true);return t;});
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),64);server.setExecutor(requests);server.createContext("/",this::handle);server.start();
    }
    private void handle(HttpExchange exchange)throws IOException{
        try{
            String supplied=exchange.getRequestHeaders().getFirst("Authorization");if(supplied==null||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))){send(exchange,401,object().put("error","Node authentication required"));return;}
            StorageGuard.verifyRuntime();String method=exchange.getRequestMethod(),path=exchange.getRequestURI().getPath(),actor=Optional.ofNullable(exchange.getRequestHeaders().getFirst("X-Ops-Actor")).orElse("");boolean write=method.equals("POST");
            require(method.equals("GET")||write,"Unsupported node method");if(write)require(!actor.isBlank()&&"admin".equals(exchange.getRequestHeaders().getFirst("X-Ops-Role")),"Authenticated administrator required");
            if(write&&readOnly&&!path.equals("/manager/plans"))require(false,"Node is read-only");
            if(path.matches("/manager/export/[a-f0-9]{64}")&&!write){String id="sha256:"+path.substring(path.lastIndexOf('/')+1);docker.call("GET","/images/"+id+"/json");try(DockerClient.Response response=docker.request("GET","/images/"+id+"/get",InputStream.nullInputStream(),0,"application/json",Duration.ofMinutes(10))){require(response.status==200,"Image export failed");exchange.getResponseHeaders().set("Content-Type","application/x-tar");exchange.sendResponseHeaders(200,0);response.body.transferTo(exchange.getResponseBody());}exchange.close();return;}
            if(path.equals("/images/import")&&write){require(!deployments.busy()&&!packages.busy(),"Node busy");long length=length(exchange);require(length>0&&length<=2L*1024*1024*1024,"Invalid TAR size");String expected=exchange.getRequestHeaders().getFirst("X-SHA256");require(expected!=null&&expected.matches("[a-f0-9]{64}"),"SHA-256 required");require(mutations.tryLock(),"Another node mutation is active; retry import later");Path temporary=null;try{temporary=Files.createTempFile(root,"image-import-",".tar");try(OutputStream out=Files.newOutputStream(temporary)){byte[] block=new byte[65536];long remaining=length;while(remaining>0){int n=exchange.getRequestBody().read(block,0,(int)Math.min(block.length,remaining));require(n>0,"Truncated TAR upload");out.write(block,0,n);remaining-=n;}}require(SignedPackageStore.sha(temporary).equals(expected),"TAR checksum mismatch");packages.loadImageArchive(temporary);send(exchange,200,object().put("sha256",expected).put("output","Offline images imported and verified"));}finally{try{if(temporary!=null)Files.deleteIfExists(temporary);}finally{mutations.unlock();}}return;}
            Object result;
            if(!write){
                if(path.startsWith("/manager/"))result=resources.get(path);
                else if(path.equals("/packages"))result=packages.catalog();
                else if(path.matches("/packages/uploads/[a-f0-9]{32}"))result=packages.status(last(path));
                else if(path.equals("/installations"))result=deployments.list();
                else if(path.matches("/installations/[a-f0-9]{32}"))result=deployments.get(last(path));
                else if(path.matches("/installations/[a-f0-9]{32}/health"))result=deployments.nativeHealth(path.split("/")[2]);
                else if(path.equals("/inventory")){ObjectNode inventory=object().put("managementOwner",resources.owner()).put("collectedAt",StateFiles.now()).put("executorImplementation","JDK");JsonNode info=docker.call("GET","/info");ObjectNode node=object();for(String key:List.of("Name","NCPU","MemTotal","ContainersRunning","DockerRootDir","ServerVersion","ID"))node.set(key,info.path(key));inventory.set("node",node);ArrayNode containers=(ArrayNode)docker.call("GET","/containers/json?all=1");for(JsonNode c:containers)((ObjectNode)c).set("managerMetrics",resources.latest(c.path("Id").asText()));inventory.set("containers",containers);inventory.set("images",docker.call("GET","/images/json"));result=inventory;}
                else if(path.matches("/containers/[a-f0-9]{64}/(inspect|stats|logs)")){String[] parts=path.split("/");if(parts[3].equals("stats"))result=resources.metrics(parts[2]);else if(parts[3].equals("logs"))result=resources.get("/manager/logs/"+parts[2]);else{ObjectNode value=(ObjectNode)redact(resources.inspect(parts[2]));if(!"admin".equals(exchange.getRequestHeaders().getFirst("X-Ops-Role"))&&value.path("Config").isObject())((ObjectNode)value.path("Config")).remove(List.of("Env","Cmd","Entrypoint","Healthcheck"));result=value;}}
                else if(path.equals("/platform/maintenance"))result=journal.list("maintenance-jobs",100);
                else{send(exchange,404,object().put("error","Unsupported node route"));return;}
            }else{
                if(path.startsWith("/manager/")||path.startsWith("/installations"))require(!packages.busy(),"Offline package verification active");
                int maximum=path.matches("/packages/uploads/[a-f0-9]{32}/[0-9]+")?4*1024*1024:1024*1024;byte[] bytes=body(exchange,maximum);
                if(path.matches("/packages/uploads/[a-f0-9]{32}/[0-9]+")){String[] parts=path.split("/");result=packages.chunk(parts[3],Long.parseLong(parts[4]),bytes);}
                else{JsonNode spec=bytes.length==0?object():StateFiles.JSON.readTree(bytes);
                    if(path.equals("/packages/uploads"))result=packages.create(spec);
                    else if(path.matches("/packages/uploads/[a-f0-9]{32}/commit")){require(!deployments.busy(),"Application deployment active; retry package commit after completion");result=packages.commit(path.split("/")[3]);}
                    else if(path.matches("/packages/[a-f0-9]{64}/retire"))result=packages.retire(path.split("/")[2],actor);
                    else if(path.equals("/manager/plans"))result=resources.plan(spec,actor);
                    else if(path.equals("/manager/execute"))result=resources.execute(spec,actor);
                    else if(path.matches("/manager/jobs/[a-f0-9]{32}/cancel"))result=resources.cancel(path.split("/")[3]);
                    else if(path.equals("/installations/preflight"))result=deployments.preflight(spec,actor);
                    else if(path.equals("/installations"))result=deployments.submit(spec,actor);
                    else if(path.matches("/installations/[a-f0-9]{32}/(resume|cancel|rollback|stop|start)"))result=deployments.control(path.split("/")[2],last(path),actor);
                    else{send(exchange,404,object().put("error","Unsupported node mutation"));return;}
                }
            }send(exchange,200,result);
        }catch(Exception error){try{send(exchange,400,object().put("error",error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()));}catch(IOException disconnected){exchange.close();}}
    }
    private static String last(String path){return path.substring(path.lastIndexOf('/')+1);}
    public AutoCloseable backupLock() {
        require(mutations.tryLock(),"Node mutation active; retry backup after completion");
        if(deployments.busy()||packages.busy()){mutations.unlock();throw new IllegalStateException("Deployment or package verification active; retry backup later");}
        return mutations::unlock;
    }
    public void backupJournal(Path destination)throws Exception{journal.backup(destination);}
    private static long length(HttpExchange exchange){String value=exchange.getRequestHeaders().getFirst("Content-Length");return value==null?-1:Long.parseLong(value);}
    private static byte[] body(HttpExchange exchange,int maximum)throws IOException{long size=length(exchange);require(size>=0&&size<=maximum,"Invalid request length");byte[] bytes=exchange.getRequestBody().readNBytes((int)size);require(bytes.length==size,"Truncated node request");return bytes;}
    private static void send(HttpExchange exchange,int status,Object value)throws IOException{byte[] bytes=StateFiles.JSON.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(status,bytes.length);try(OutputStream out=exchange.getResponseBody()){out.write(bytes);}exchange.close();}
    public void close(){server.stop(0);requests.shutdownNow();resources.close();deployments.close();packages.close();try{journal.close();}catch(Exception ignored){}}
}
