package com.chatchat.deployment.execution;

import com.chatchat.deployment.docker.*;
import com.chatchat.deployment.protocol.InstallationManifest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import static com.chatchat.deployment.execution.ExecutionJson.*;
import static com.chatchat.deployment.protocol.InstallationManifest.require;

/** Reviewed Docker resource operations with H2 idempotency and strict instance ownership. */
public final class ResourceManager implements AutoCloseable {
    private final DockerClient docker;private final ExecutionJournal journal;private final String owner;private final Path data;
    private final ReentrantLock mutationLock;private final boolean readOnly;
    private BooleanSupplier installationBusy=()->false;
    private final ScheduledExecutorService samples=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"jdk-resource-sampler");t.setDaemon(true);return t;});
    private final ExecutorService operations=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"jdk-resource-worker");t.setDaemon(true);return t;});
    private final Map<String,JsonNode> previousCounters=new ConcurrentHashMap<>();
    public ResourceManager(DockerClient docker,ExecutionJournal journal,String owner,Path data,ReentrantLock mutationLock,boolean readOnly)throws Exception{
        this.docker=docker;this.journal=journal;this.owner=owner;this.data=data;this.mutationLock=mutationLock;this.readOnly=readOnly;Files.createDirectories(data);
        for(JsonNode record:journal.list("resource-jobs",100000))if(Set.of("PENDING","RUNNING").contains(record.path("status").asText())){ObjectNode job=(ObjectNode)record;job.put("status","UNKNOWN").put("error","Executor restarted; inspect the actual Docker object before a fresh approved operation").put("updated",StateFiles.now());journal.write("resource-jobs",job.path("id").asText(),job);}
        HostMetrics host=new HostMetrics(com.chatchat.deployment.storage.StorageGuard.dataDirectory());
        samples.scheduleWithFixedDelay(()->{try{sample("host",host.sample());}catch(Exception ignored){}},0,10,TimeUnit.SECONDS);
        samples.scheduleWithFixedDelay(()->{try{for(JsonNode container:docker.call("GET","/containers/json?all=1")){if(Thread.currentThread().isInterrupted())break;if(container.path("State").asText().equals("running"))try{metrics(container.path("Id").asText());}catch(Exception ignored){}}}catch(Exception ignored){}},1,30,TimeUnit.SECONDS);
    }
    public void installationBusy(BooleanSupplier value){installationBusy=value;}
    public String owner(){return owner;}
    public ObjectNode policy(){ObjectNode policy=object().put("readOnly",readOnly).put("approvalRequired",true).put("terminalSupported",false).put("hostBindMountsSupported",false);policy.set("allowedRegistries",array());return policy;}
    public static String id(String value){require(value!=null&&value.matches("[a-f0-9]{64}"),"Full container ID required");return value;}
    private static String reference(String value){require(value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,250}"),"Invalid local image reference");return value;}
    public JsonNode inspect(String ident)throws IOException{return docker.call("GET","/containers/"+id(ident)+"/json");}
    private boolean stageComponent(JsonNode labels){return labels.path("deployment-ops.owner").asText().equals(owner)&&labels.path("com.docker.compose.project").asText().matches("ops-env-[a-z0-9-]{1,32}")&&!labels.path("deployment-ops.component").asText().isBlank();}
    private JsonNode mutable(String ident)throws IOException{
        JsonNode item=inspect(ident),labels=item.path("Config").path("Labels");
        require(!labels.path("com.docker.compose.project").asText().startsWith("deployment-ops")&&!labels.path("deployment-ops.control-plane").asText().equals("true"),"Control plane is protected");
        require(!labels.path("deployment-ops.installation").asText().equals("true"),"Use the owning application lifecycle workflow");
        require((labels.path("deployment-ops.manager").asText().equals("true")||labels.path("deployment-ops.managed").asText().equals("true")||stageComponent(labels))&&labels.path("deployment-ops.owner").asText().equals(owner),"External containers are discovery-only");return item;
    }
    private JsonNode image(String ident)throws IOException{require(ident.matches("sha256:[a-f0-9]{64}"),"Immutable image ID required");return docker.call("GET","/images/"+url(ident)+"/json");}
    private ArrayNode references(String image)throws IOException{ArrayNode result=array();for(JsonNode c:docker.call("GET","/containers/json?all=1"))if(c.path("ImageID").asText().equals(image))result.add(object().put("id",c.path("Id").asText()).set("names",c.path("Names")));return result;}
    private static void fields(JsonNode spec,String allowed){Set<String> keys=Set.of(allowed.split(","));spec.fieldNames().forEachRemaining(k->require(keys.contains(k),"Unsupported configuration: "+k));}
    public ObjectNode createConfig(JsonNode input)throws Exception{
        ObjectNode spec=object();JsonNode defaults=journal.read("image-defaults",input.path("image").asText().replace("sha256:",""));if(defaults!=null)spec.setAll((ObjectNode)defaults);spec.setAll((ObjectNode)input);
        fields(spec,"action,name,image,command,environment,ports,volumes,network,memoryMiB,cpus,restart,healthcheck,application,user,entrypoint,workingDir");
        require(spec.path("name").asText().matches("[a-z][a-z0-9_.-]{2,63}"),"Container name must have 3..64 lowercase characters");
        JsonNode image=docker.call("GET","/images/"+url(reference(spec.path("image").asText()))+"/json");
        for(JsonNode c:docker.call("GET","/containers/json?all=1"))for(JsonNode name:c.path("Names"))require(!name.asText().equals("/"+spec.path("name").asText()),"Container name exists");
        JsonNode environment=spec.path("environment");require(environment.isMissingNode()||environment.isObject()&&environment.size()<=100,"Invalid environment map");ArrayNode env=array();environment.fields().forEachRemaining(e->{require(e.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")&&e.getValue().isTextual()&&e.getValue().asText().length()<=16384,"Invalid environment value");String value=e.getValue().asText();if(value.equals("[REDACTED]")){require(defaults!=null&&defaults.path("environment").has(e.getKey()),"Re-enter masked environment value");value=defaults.path("environment").path(e.getKey()).asText();}env.add(e.getKey()+"="+value);});
        JsonNode command=spec.path("command");require(command.isMissingNode()||command.isArray()&&command.size()<=100,"Command argv required");for(JsonNode arg:command)require(arg.isTextual()&&arg.asText().length()<=4096,"Invalid command argument");
        double cpu=spec.path("cpus").asDouble(1);long memory=spec.path("memoryMiB").asLong(256);require(Double.isFinite(cpu)&&cpu>=.1&&cpu<=64&&memory>=16&&memory<=131072,"Invalid CPU or memory limit");
        String network=spec.path("network").asText("bridge");JsonNode net=docker.call("GET","/networks/"+url(network));require(Set.of("bridge","null").contains(net.path("Driver").asText())&&!net.path("Name").asText().equals("host"),"Only bridge or none networks supported");
        ObjectNode ports=object();for(JsonNode port:spec.path("ports")){fields(port,"container,host,address");int internal=port.path("container").asInt(),host=port.path("host").asInt();require(internal>0&&internal<=65535&&host>=1024&&host<=65535,"Invalid port mapping");String address=port.path("address").asText("127.0.0.1");require(address.matches("[0-9.]+|[0-9a-fA-F:]+"),"IP literal required");java.net.InetAddress.getByName(address);ports.set(internal+"/tcp",array().add(object().put("HostIp",address).put("HostPort",Integer.toString(host))));}
        ArrayNode mounts=array();Set<String> targets=new HashSet<>();for(JsonNode volume:spec.path("volumes")){fields(volume,"source,target,readOnly");String source=volume.path("source").asText();require(source.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"),"Named volume required");String target=InstallationManifest.containerPath(volume.path("target").asText());JsonNode info=docker.call("GET","/volumes/"+url(source)),labels=info.path("Labels");boolean own=labels.path("deployment-ops.manager").asText().equals("true")&&labels.path("deployment-ops.owner").asText().equals(owner);require(own||volume.path("readOnly").asBoolean(),"External volumes may only be mounted read-only");targets.add(target);mounts.add(object().put("Type","volume").put("Source",source).put("Target",target).put("ReadOnly",volume.path("readOnly").asBoolean()));}
        if(!spec.path("action").asText().equals("image.defaults.validate"))image.path("Config").path("Volumes").fieldNames().forEachRemaining(target->require(targets.contains(target),"Map every image-declared volume on the data disk"));
        String restart=spec.path("restart").asText("unless-stopped");require(Set.of("no","unless-stopped","on-failure","always").contains(restart),"Invalid restart policy");
        ObjectNode config=object().put("Image",image.path("Id").asText());if(command.isArray()&&command.size()>0)config.set("Cmd",command);config.set("Env",env);config.set("Labels",object().put("deployment-ops.manager","true").put("deployment-ops.owner",owner).put("deployment-ops.application",spec.path("application").asText(spec.path("name").asText())));
        ObjectNode exposed=object();ports.fieldNames().forEachRemaining(p->exposed.set(p,object()));config.set("ExposedPorts",exposed);
        ObjectNode host=object().put("NetworkMode",network).put("Memory",memory*1024*1024).put("NanoCpus",(long)(cpu*1e9)).put("PidsLimit",256);host.set("PortBindings",ports);host.set("Mounts",mounts);host.set("RestartPolicy",object().put("Name",restart));host.set("CapDrop",array().add("ALL"));host.set("SecurityOpt",array().add("no-new-privileges:true"));config.set("HostConfig",host);
        if(spec.has("entrypoint")){require(spec.path("entrypoint").isArray()&&spec.path("entrypoint").size()<=100,"Entrypoint argv required");for(JsonNode v:spec.path("entrypoint"))require(v.isTextual()&&v.asText().length()<=4096,"Invalid entrypoint");config.set("Entrypoint",spec.path("entrypoint"));}
        if(spec.has("workingDir")&&!spec.path("workingDir").asText().isBlank())config.put("WorkingDir",InstallationManifest.containerPath(spec.path("workingDir").asText()));
        if(spec.hasNonNull("user")&&!spec.path("user").asText().isBlank()){require(spec.path("user").asText().matches("[0-9]{1,10}:[0-9]{1,10}"),"Numeric UID:GID required");config.put("User",spec.path("user").asText());}
        if(spec.has("healthcheck")){JsonNode h=spec.path("healthcheck");fields(h,"command,intervalSeconds,timeoutSeconds,retries");require(h.path("command").isArray()&&h.path("command").size()>0,"Health argv required");ArrayNode test=array().add("CMD");for(JsonNode arg:h.path("command")){require(arg.isTextual(),"Health argv strings required");test.add(arg);}int interval=h.path("intervalSeconds").asInt(30),timeout=h.path("timeoutSeconds").asInt(5),retries=h.path("retries").asInt(3);require(interval>=1&&interval<=3600&&timeout>=1&&timeout<=300&&retries>=1&&retries<=20,"Invalid health timing");ObjectNode health=object().put("Interval",interval*1_000_000_000L).put("Timeout",timeout*1_000_000_000L).put("Retries",retries);health.set("Test",test);config.set("Healthcheck",health);}
        return config;
    }
    private ObjectNode snapshot(JsonNode spec)throws Exception{
        String action=spec.path("action").asText();
        if(Set.of("container.start","container.stop","container.restart","container.delete","container.update").contains(action)){
            JsonNode item=mutable(spec.path("id").asText());if(action.equals("container.delete"))require(!stageComponent(item.path("Config").path("Labels")),"Application components cannot be deleted individually");if(action.equals("container.update"))updateConfig(spec);ArrayNode dependents=array();String ident=item.path("Id").asText(),name=item.path("Name").asText().replaceFirst("^/","");
            for(JsonNode child:docker.call("GET","/containers/json?all=1"))if(Set.of("container:"+ident,"container:"+name).contains(child.path("HostConfig").path("NetworkMode").asText()))dependents.add(child.path("Id"));
            require(Set.of("container.start","container.update").contains(action)||dependents.isEmpty(),"Dependent network namespaces require application lifecycle management");require(!action.equals("container.delete")||!item.path("State").path("Running").asBoolean(),"Stop container before deletion; force forbidden");
            ObjectNode state=object(),host=object();for(String field:List.of("Running","Status","StartedAt","FinishedAt","Restarting","ExitCode"))state.set(field,item.path("State").path(field));for(String field:List.of("Memory","NanoCpus","NetworkMode","PortBindings","Binds","Mounts","Privileged","CapAdd","CapDrop","RestartPolicy"))host.set(field,item.path("HostConfig").path(field));
            ObjectNode result=object().put("id",ident).put("name",name).put("image",item.path("Image").asText()).put("source",item.path("Config").path("Labels").path("com.docker.compose.project").asText("standalone"));result.set("state",state);result.set("hostConfig",host);result.set("config",item.path("Config"));result.set("mounts",item.path("Mounts"));result.set("dependents",dependents);if(action.equals("container.update"))result.set("proposed",updateConfig(spec));return result;
        }
        if(action.equals("image.defaults")){String ident=spec.path("id").asText();JsonNode img=image(ident);ObjectNode proposed=profile(spec);ObjectNode check=proposed.deepCopy();check.put("image",ident).put("name","ops-profile-"+StateFiles.id()).put("action","image.defaults.validate");createConfig(check);ObjectNode result=object().put("id",ident).put("name",ident);result.set("proposed",proposed);result.set("previous",journal.read("image-defaults",ident.substring(7)));result.set("image",img.path("Config"));return result;}
        if(action.equals("container.create")){ObjectNode result=object().put("name",spec.path("name").asText());result.set("config",createConfig(spec));return result;}
        if(Set.of("image.delete","image.tag").contains(action)){
            JsonNode image=image(spec.path("id").asText());ArrayNode refs=references(image.path("Id").asText());require(!action.equals("image.delete")||refs.isEmpty(),"Image is referenced by a running or stopped container");
            if(action.equals("image.tag")){String target=reference(spec.path("repository").asText()+":"+spec.path("tag").asText());for(JsonNode i:docker.call("GET","/images/json"))for(JsonNode tag:i.path("RepoTags"))require(!tag.asText().equals(target),"Target image tag exists; overwriting forbidden");}
            ObjectNode result=object().put("name",image.path("Id").asText()).put("id",image.path("Id").asText());result.set("tags",image.path("RepoTags"));result.set("references",refs);return result;
        }
        if(action.equals("volume.create")){String name=spec.path("name").asText();require(name.matches("ops-[a-z0-9][a-z0-9_.-]{1,60}"),"New volumes require an ops- name");for(JsonNode volume:docker.call("GET","/volumes").path("Volumes"))require(!volume.path("Name").asText().equals(name),"Volume already exists");return object().put("name",name);}
        throw new IllegalArgumentException("Unsupported offline resource operation");
    }
    public synchronized ObjectNode plan(JsonNode spec,String actor)throws Exception{
        require(!actor.isBlank()&&StateFiles.JSON.writeValueAsBytes(spec).length<=65536,"Authenticated bounded operation required");ObjectNode snapshot=snapshot(spec);String id=StateFiles.id();ObjectNode preview=object().put("id",id).put("action",spec.path("action").asText()).put("target",snapshot.path("name").asText()).put("preservesVolumes",true).put("readOnly",readOnly).put("expiresAt",StateFiles.now()+300).put("externalManagement",!snapshot.path("source").asText("standalone").equals("standalone"));preview.set("snapshot",redact(snapshot));
        ObjectNode record=object().put("actor",actor).put("snapshot",fingerprint(snapshot)).put("used",false);record.set("spec",spec);record.set("preview",preview);journal.write("resource-plans",id,record);journal.expire("resource-plans",StateFiles.now()-86400);return preview;
    }
    public synchronized JsonNode execute(JsonNode input,String actor)throws Exception{
        require(!readOnly,"Node is read-only");require(!installationBusy.getAsBoolean(),"Application deployment active; retry after completion");String key=input.path("idempotencyKey").asText();require(key.matches("[A-Za-z0-9_-]{12,100}"),"Idempotency key required");String requestHash=fingerprint(input);JsonNode prior=journal.read("resource-idempotency",key);
        if(prior!=null){require(prior.path("actor").asText().equals(actor)&&prior.path("hash").asText().equals(requestHash),"Idempotency key conflict");return job(prior.path("job").asText());}
        String planId=input.path("planId").asText();require(planId.matches("[a-f0-9]{32}"),"Plan required");ObjectNode plan=(ObjectNode)journal.read("resource-plans",planId);require(plan!=null&&plan.path("actor").asText().equals(actor)&&!plan.path("used").asBoolean()&&plan.path("preview").path("expiresAt").asDouble()>StateFiles.now(),"Approval plan missing, consumed or expired");
        require(input.path("confirmation").asText().equals(plan.path("preview").path("target").asText()),"Exact target confirmation required");JsonNode spec=plan.path("spec");require(fingerprint(snapshot(spec)).equals(plan.path("snapshot").asText()),"Docker resource changed; obtain a new plan");
        String ident=StateFiles.id();ObjectNode job=object().put("id",ident).put("actor",actor).put("status","PENDING").put("action",spec.path("action").asText()).put("target",plan.path("preview").path("target").asText()).put("created",StateFiles.now()).put("updated",StateFiles.now());job.putNull("error").putNull("result");plan.put("used",true);
        journal.write(List.of(new ExecutionJournal.Entry("resource-plans",planId,plan),new ExecutionJournal.Entry("resource-jobs",ident,job),new ExecutionJournal.Entry("resource-idempotency",key,object().put("actor",actor).put("hash",requestHash).put("job",ident))));
        String expected=plan.path("snapshot").asText();operations.submit(()->run(ident,spec,expected));return job;
    }
    private void run(String ident,JsonNode spec,String expected){
        mutationLock.lock();try{
            ObjectNode job;synchronized(this){job=(ObjectNode)journal.read("resource-jobs",ident);if(job.path("status").asText().equals("CANCELLED"))return;job.put("status","RUNNING").put("updated",StateFiles.now());journal.write("resource-jobs",ident,job);}
            try{
                require(!installationBusy.getAsBoolean(),"Application deployment active");require(fingerprint(snapshot(spec)).equals(expected),"Resource changed after approval");String action=spec.path("action").asText(),id=spec.path("id").asText();JsonNode result;
                switch(action){
                    case "container.create"->result=docker.call("POST","/containers/create?name="+url(spec.path("name").asText()),createConfig(spec));
                    case "image.defaults"->{journal.write("image-defaults",id.substring(7),profile(spec));result=object().put("saved",true).put("appliesTo","future-containers");}
                    case "container.update"->result=docker.call("POST","/containers/"+id+"/update",updateConfig(spec));
                    case "container.delete"->result=docker.call("DELETE","/containers/"+id+"?force=false&v=false");
                    case "container.start","container.stop","container.restart"->{result=docker.call("POST","/containers/"+id+"/"+action.substring(10)+"?t=20");if(!action.equals("container.stop")){Thread.sleep(300);JsonNode state=inspect(id).path("State");require(state.path("Running").asBoolean(),"Container exited after startup: "+state.path("ExitCode").asInt());}}
                    case "image.delete"->result=deleteUnusedImage(id);
                    case "image.tag"->result=docker.call("POST","/images/"+url(id)+"/tag?repo="+url(spec.path("repository").asText())+"&tag="+url(spec.path("tag").asText()));
                    case "volume.create"->{String name=spec.path("name").asText();Path target=data.resolve("volumes").resolve(name);Files.createDirectories(target);require(target.toRealPath().startsWith(data.toRealPath()),"Volume escapes managed data root");ObjectNode config=object().put("Name",name).put("Driver","local");config.set("Labels",object().put("deployment-ops.manager","true").put("deployment-ops.owner",owner));config.set("DriverOpts",object().put("type","none").put("o","bind").put("device",target.toString()));result=docker.call("POST","/volumes/create",config);}
                    default->throw new IllegalArgumentException("Unsupported operation");
                }
                job.put("status","SUCCESS");job.set("result",redact(result));
            }catch(Exception failure){job.put("status","FAILED").put("error",safeError(failure,spec));}
            job.put("updated",StateFiles.now());journal.write(List.of(new ExecutionJournal.Entry("resource-jobs",ident,job),new ExecutionJournal.Entry("resource-audit",ident,job)));
        }catch(Exception ignored){/* Durable RUNNING record becomes UNKNOWN after process recovery. */}finally{mutationLock.unlock();}
    }
    private JsonNode deleteUnusedImage(String ident)throws Exception{
        JsonNode current=image(ident);require(references(ident).isEmpty(),"Image acquired a container reference after approval");List<String> tags=new ArrayList<>();for(JsonNode tag:current.path("RepoTags"))if(!tag.asText().equals("<none>:<none>"))tags.add(tag.asText());ArrayNode result=array();
        // Docker refuses a non-forced ID deletion with multiple tags. Remove only
        // aliases still bound to the approved unused ID, then remove that ID.
        while(tags.size()>1){String tag=tags.remove(0);require(references(ident).isEmpty(),"Image became referenced; deletion stopped");require(docker.call("GET","/images/"+url(tag)+"/json").path("Id").asText().equals(ident),"Image tag changed after approval");JsonNode removed=docker.call("DELETE","/images/"+url(tag)+"?force=false&noprune=true");for(JsonNode value:removed)result.add(value);}
        for(JsonNode value:docker.call("DELETE","/images/"+url(ident)+"?force=false&noprune=true"))result.add(value);return result;
    }
    private static String safeError(Exception failure,JsonNode spec){String text=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();Iterator<Map.Entry<String,JsonNode>> values=spec.path("environment").fields();while(values.hasNext()){var e=values.next();if(e.getKey().matches("(?i).*(password|secret|token|credential|key).*")){String value=e.getValue().asText();if(!value.isEmpty())text=text.replace(value,"[REDACTED]");}}return text.substring(0,Math.min(text.length(),2000));}
    public JsonNode job(String id)throws Exception{require(id.matches("[a-f0-9]{32}"),"Invalid job ID");JsonNode record=journal.read("resource-jobs",id);require(record!=null,"Job not found");return record;}
    public synchronized JsonNode cancel(String id)throws Exception{ObjectNode job=(ObjectNode)job(id);require(job.path("status").asText().equals("PENDING"),"Only pending operations can be cancelled");job.put("status","CANCELLED").put("updated",StateFiles.now());journal.write("resource-jobs",id,job);return job;}
    private synchronized void sample(String resource,ObjectNode value)throws Exception{JsonNode old=journal.read("metrics",resource);ArrayNode history=old!=null&&old.isArray()?(ArrayNode)old:array();ObjectNode entry=value.deepCopy();entry.put("at",StateFiles.now());history.add(entry);while(history.size()>120)history.remove(0);journal.write("metrics",resource,history);}
    public JsonNode latest(String resource)throws Exception{JsonNode history=journal.read("metrics",resource);return history==null||history.isEmpty()?NullNode.instance:history.get(history.size()-1);}
    public ObjectNode metrics(String ident)throws Exception{JsonNode raw=docker.call("GET","/containers/"+id(ident)+"/stats?stream=false&one-shot=true"),previous=previousCounters.put(ident,raw);ObjectNode current=raw.deepCopy();if(previous!=null)current.set("precpu_stats",previous.path("cpu_stats"));ObjectNode metrics=HostMetrics.container(current);if(previous==null&&raw.path("precpu_stats").path("cpu_usage").path("total_usage").asLong()==0)metrics.putNull("cpuPercent");sample(ident,metrics);return metrics;}
    public static String logs(DockerClient docker,String ident)throws IOException{
        try(DockerClient.Response response=docker.request("GET","/containers/"+id(ident)+"/logs?stdout=1&stderr=1&timestamps=1&tail=200",InputStream.nullInputStream(),0,"application/json",Duration.ofSeconds(20))){require(response.status<300,"Docker logs unavailable");byte[] bytes=response.body.readNBytes(2*1024*1024);ByteArrayOutputStream out=new ByteArrayOutputStream();int offset=0;
            while(offset+8<=bytes.length&&bytes[offset]>=0&&bytes[offset]<=2&&bytes[offset+1]==0&&bytes[offset+2]==0&&bytes[offset+3]==0){int length=ByteBuffer.wrap(bytes,offset+4,4).getInt();if(length<0||offset+8L+length>bytes.length)break;out.write(bytes,offset+8,length);offset+=8+length;}
            String text=new String(offset==0?bytes:out.toByteArray(),StandardCharsets.UTF_8);return text.substring(Math.max(0,text.length()-131072));
        }
    }
    private ObjectNode updateConfig(JsonNode spec){
        fields(spec,"action,id,memoryMiB,cpus,restart");double cpu=spec.path("cpus").asDouble();long memory=spec.path("memoryMiB").asLong();String restart=spec.path("restart").asText();
        require(Double.isFinite(cpu)&&cpu>=.1&&cpu<=64&&memory>=16&&memory<=131072,"Invalid CPU or memory limit");require(Set.of("no","unless-stopped","on-failure","always").contains(restart),"Invalid restart policy");
        ObjectNode out=object().put("Memory",memory*1024*1024).put("NanoCpus",(long)(cpu*1e9));out.set("RestartPolicy",object().put("Name",restart));return out;
    }
    private ObjectNode profile(JsonNode spec)throws Exception{
        fields(spec,"action,id,configuration");JsonNode value=spec.path("configuration");require(value.isObject(),"Configuration object required");fields(value,"command,entrypoint,environment,ports,volumes,network,memoryMiB,cpus,restart,healthcheck,user,workingDir");
        ObjectNode out=value.deepCopy();JsonNode old=journal.read("image-defaults",spec.path("id").asText().replace("sha256:",""));
        if(out.path("environment").isObject())for(var it=out.path("environment").fields();it.hasNext();){var entry=it.next();if(entry.getValue().asText().equals("[REDACTED]")){JsonNode prior=old==null?null:old.path("environment").get(entry.getKey());if(prior==null)throw new IllegalArgumentException("Re-enter the masked environment value: "+entry.getKey());((ObjectNode)out.path("environment")).set(entry.getKey(),prior);}}
        return out;
    }
    private JsonNode readableConfig(JsonNode config){ObjectNode out=((ObjectNode)config).deepCopy();out.remove("Env");ObjectNode environment=object();for(JsonNode item:config.path("Env")){String[] pair=item.asText().split("=",2);if(pair.length==2)environment.put(pair[0],pair[1]);}out.set("environment",environment);return redact(out);}
    public JsonNode get(String path)throws Exception{
        if(path.matches("/manager/images/sha256:[a-f0-9]{64}")){String ident=path.substring(path.lastIndexOf('/')+1);JsonNode img=image(ident);ObjectNode out=object().put("id",ident);out.set("image",readableConfig(img.path("Config")));JsonNode defaults=journal.read("image-defaults",ident.substring(7));out.set("defaults",defaults==null?object():redact(defaults));return out;}
        if(path.matches("/manager/containers/[a-f0-9]{64}/configuration")){String ident=path.split("/")[3];JsonNode item=inspect(ident);ObjectNode out=object();out.set("current",readableConfig(item.path("Config")));out.set("host",redact(item.path("HostConfig")));out.set("mounts",redact(item.path("Mounts")));try{mutable(ident);out.put("mutable",true);}catch(IllegalArgumentException denied){out.put("mutable",false).put("reason",denied.getMessage());}return out;}
        if(path.equals("/manager/policy"))return policy();if(path.equals("/manager/jobs"))return StateFiles.JSON.valueToTree(journal.list("resource-jobs",300));if(path.matches("/manager/jobs/[a-f0-9]{32}"))return job(path.substring(path.lastIndexOf('/')+1));
        if(path.equals("/manager/networks"))return docker.call("GET","/networks");if(path.equals("/manager/volumes"))return docker.call("GET","/volumes");
        if(path.equals("/manager/host")){JsonNode value=latest("host");return value.isNull()?object().put("available",false).put("reason","Waiting for host metrics"):value;}
        if(path.equals("/manager/history")){JsonNode value=journal.read("metrics","host");return value==null?array():value;}
        if(path.equals("/manager/events")){long now=System.currentTimeMillis()/1000;ArrayNode events=array();try(DockerClient.Response response=docker.request("GET","/events?since="+(now-300)+"&until="+now,InputStream.nullInputStream(),0,"application/json",Duration.ofSeconds(10))){require(response.status<300,"Docker events unavailable");byte[] bytes=response.body.readNBytes(4*1024*1024+1);require(bytes.length<=4*1024*1024,"Event response too large");for(String line:new String(bytes,StandardCharsets.UTF_8).split("\n"))if(!line.isBlank())events.add(redact(StateFiles.JSON.readTree(line)));}return events;}
        if(path.matches("/manager/logs/[a-f0-9]{64}")){String ident=path.substring(path.lastIndexOf('/')+1),text=logs(docker,ident);for(JsonNode item:inspect(ident).path("Config").path("Env")){String[] e=item.asText().split("=",2);if(e.length==2&&!e[1].isEmpty()&&e[0].matches("(?i).*(password|token|secret|credential|key).*"))text=text.replace(e[1],"[REDACTED]");}return object().put("text",text).put("collectedAt",StateFiles.now()).put("tail",200);}
        if(path.matches("/manager/containers/[a-f0-9]{64}/(metrics|history)")){String[] fields=path.split("/");if(fields[4].equals("metrics"))return metrics(fields[3]);JsonNode value=journal.read("metrics",fields[3]);return value==null?array():value;}
        throw new IllegalArgumentException("Unsupported resource read");
    }
    public void close(){operations.shutdownNow();samples.shutdownNow();}
}
