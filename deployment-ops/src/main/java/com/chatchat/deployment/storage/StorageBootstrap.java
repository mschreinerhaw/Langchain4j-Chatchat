package com.chatchat.deployment.storage;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** First-run storage selection before H2 opens; uses only the embedded JDK HTTP server. */
public final class StorageBootstrap {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<String> KEYS=List.of("OPS_DATA_DIR","OPS_RUNTIME_DIR","OPS_REQUIRED_MOUNT","OPS_STORAGE_MOUNT","OPS_RUNTIME_MOUNT");
    private StorageBootstrap(){}
    public static Path jarPath(){
        String[] arguments=ProcessHandle.current().info().arguments().orElse(new String[0]);
        for(int i=0;i+1<arguments.length;i++)if(arguments[i].equals("-jar"))return Path.of(arguments[i+1]).toAbsolutePath().normalize();
        throw new IllegalArgumentException("Launch with java -jar");
    }
    public static String[] configure(String[] arguments)throws Exception{
        List<String> remaining=new ArrayList<>();boolean explicit=System.getenv("OPS_DATA_DIR")!=null;Path config=jarPath().resolveSibling(jarPath().getFileName()+".storage.properties");
        Map<String,String> names=Map.of("--data-dir","OPS_DATA_DIR","--runtime-dir","OPS_RUNTIME_DIR","--required-mount","OPS_REQUIRED_MOUNT");
        for(int i=0;i<arguments.length;i++){
            String arg=arguments[i],flag=arg.contains("=")?arg.substring(0,arg.indexOf('=')):arg;
            if(names.containsKey(flag)||flag.equals("--storage-config")){
                String value=arg.contains("=")?arg.substring(arg.indexOf('=')+1):arguments[++i];
                if(flag.equals("--storage-config"))config=Path.of(value).toAbsolutePath().normalize();else{System.setProperty(names.get(flag),value);if(flag.equals("--data-dir"))explicit=true;}
            }else remaining.add(arg);
        }
        if(!explicit&&Files.exists(config)){
            StorageGuard.inputFile(config);Properties properties=new Properties();try(var in=Files.newInputStream(config)){properties.load(in);}for(String key:KEYS)if(properties.containsKey(key))System.setProperty(key,properties.getProperty(key));explicit=true;
        }
        if(!explicit){if(!remaining.isEmpty())throw new IllegalArgumentException("Select instance storage with --data-dir, OPS_DATA_DIR or --storage-config before running maintenance commands");serve(config);}
        validateSelection(StorageOptions.data().toString(),StorageOptions.runtime().toString(),StorageOptions.requiredMount());
        System.setProperty("OPS_DATA_DIR",StorageOptions.data().toString());System.setProperty("OPS_RUNTIME_DIR",StorageOptions.runtime().toString());
        if(StorageOptions.value("OPS_STORAGE_MOUNT","").isBlank())System.setProperty("OPS_STORAGE_MOUNT",StorageGuard.mountPoint(StorageOptions.data()));
        if(StorageOptions.value("OPS_RUNTIME_MOUNT","").isBlank())System.setProperty("OPS_RUNTIME_MOUNT",StorageGuard.mountPoint(StorageOptions.runtime()));
        return remaining.toArray(String[]::new);
    }
    public static Map<String,Object> validateSelection(String dataText,String runtimeText,String required)throws Exception{
        Path data=safeLocation(dataText),runtime=safeLocation(runtimeText);if(data.getParent()==null||data.getParent().getNameCount()<2)throw new IllegalArgumentException("Choose a dedicated platform directory, e.g. /opt/deployment-ops/data");
        if(runtime.resolve("0000-000000000000/n/1/w/"+"0".repeat(36)+"/bridge/broker.sock").toString().getBytes(StandardCharsets.UTF_8).length>107)throw new IllegalArgumentException("Application directory is too long for Unix sockets; select a shorter application path");
        for(Path path:List.of(data,runtime)){
            Path ancestor=path;while(!Files.exists(ancestor))ancestor=ancestor.getParent();
            if(!Files.isDirectory(ancestor)||!Files.isWritable(ancestor))throw new IllegalArgumentException("Directory is not writable: "+path);
            StorageGuard.probeWritable(ancestor);
        }
        if(!required.isBlank()){
            Path mount=safeLocation(required);for(Path path:List.of(data,runtime))StorageGuard.validate(mount,path,Files.readAllLines(Path.of("/proc/self/mountinfo")));
            if(Files.getAttribute(mount,"unix:dev").equals(Files.getAttribute(Path.of("/"),"unix:dev")))throw new IllegalArgumentException("Dedicated mount mode requires a separate data filesystem");
        }
        Path ancestor=data;while(!Files.exists(ancestor))ancestor=ancestor.getParent();var store=Files.getFileStore(ancestor);long free=store.getUsableSpace();if(free<512L*1024*1024)throw new IllegalArgumentException("At least 512 MiB free space is required to initialize the platform");
        return Map.of("dataDirectory",data.toString(),"runtimeDirectory",runtime.toString(),"mountPoint",StorageGuard.mountPoint(data),"filesystem",store.type(),"freeBytes",free,"totalBytes",store.getTotalSpace(),"systemFilesystem",Files.getAttribute(ancestor,"unix:dev").equals(Files.getAttribute(Path.of("/"),"unix:dev")),"requiredMount",required);
    }
    static Path safeLocation(String value)throws Exception{
        if(value==null||!value.matches("/[A-Za-z0-9_./-]+"))throw new IllegalArgumentException("Use an absolute directory containing letters, numbers, dash, underscore and slash");
        Path path=Path.of(value);if(!path.equals(path.normalize())||path.getNameCount()<2)throw new IllegalArgumentException("Choose a dedicated normalized storage directory");
        for(String prefix:List.of("/proc","/sys","/dev","/run","/tmp","/etc","/boot","/usr","/bin","/sbin"))if(path.startsWith(prefix))throw new IllegalArgumentException("System/temporary directories cannot contain platform data: "+path);
        StorageGuard.noSymlinks(path);return path;
    }
    private static void serve(Path config)throws Exception{
        String token=UUID.randomUUID().toString()+UUID.randomUUID();System.setProperty("OPS_SETUP_TOKEN",token);
        String bind=System.getenv().getOrDefault("OPS_BIND","127.0.0.1");int port=Integer.parseInt(System.getenv().getOrDefault("OPS_PORT","19240"));
        CountDownLatch configured=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(4);HttpServer server=HttpServer.create(new InetSocketAddress(bind,port),32);server.setExecutor(workers);
        server.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            if(path.equals("/api/storage/status")&&method.equals("GET")){send(exchange,200,Map.of("requiresConfiguration",true,"defaultDataDirectory",StorageOptions.data().toString(),"defaultRuntimeDirectory",StorageOptions.runtime().toString()));return;}
            if(path.startsWith("/api/storage/")&&method.equals("POST")){
                if(!"1".equals(exchange.getRequestHeaders().getFirst("X-Ops-Request")))throw new IllegalArgumentException("Installation request header required");
                byte[] bytes=exchange.getRequestBody().readNBytes(8193);if(bytes.length>8192)throw new IllegalArgumentException("Request too large");JsonNode input=JSON.readTree(bytes);
                if(!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),input.path("token").asText().getBytes(StandardCharsets.UTF_8))){send(exchange,403,Map.of("error","Installation authorization token rejected"));return;}
                Map<String,Object> review=validateSelection(input.path("dataDirectory").asText(),input.path("runtimeDirectory").asText(),input.path("requiredMount").asText());
                if(path.equals("/api/storage/check")){send(exchange,200,review);return;}
                if(!path.equals("/api/storage/apply"))throw new IllegalArgumentException("Unknown installation operation");
                synchronized(configured){
                    if(configured.getCount()==0)throw new IllegalArgumentException("Storage already configured");
                    StorageGuard.noSymlinks(config);if(Files.exists(config))throw new IllegalArgumentException("Storage configuration exists; explicit migration is required");
                    Properties properties=new Properties();properties.setProperty("OPS_DATA_DIR",review.get("dataDirectory").toString());properties.setProperty("OPS_RUNTIME_DIR",review.get("runtimeDirectory").toString());properties.setProperty("OPS_REQUIRED_MOUNT",review.get("requiredMount").toString());properties.setProperty("OPS_STORAGE_MOUNT",review.get("mountPoint").toString());properties.setProperty("OPS_RUNTIME_MOUNT",StorageGuard.mountPoint(Path.of(review.get("runtimeDirectory").toString())));
                    Files.createDirectories(config.getParent());Path temporary=Files.createTempFile(config.getParent(),"storage-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    try{try(var out=Files.newOutputStream(temporary)){properties.store(out,"Installation storage selection; retain with the JAR for direct launches");}Files.move(temporary,config,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temporary);}
                    properties.forEach((k,v)->System.setProperty(k.toString(),v.toString()));send(exchange,200,Map.of("configured",true));configured.countDown();return;
                }
            }
            if(path.startsWith("/api/")){send(exchange,503,Map.of("error","Complete storage selection before platform initialization"));return;}
            if(!method.equals("GET")||path.contains("..")){send(exchange,404,Map.of("error","Not found"));return;}
            String resource="/static"+(path.equals("/")?"/index.html":path);try(var stream=StorageBootstrap.class.getResourceAsStream(resource)){
                if(stream==null){send(exchange,404,Map.of("error","Not found"));return;}
                byte[] data=stream.readAllBytes();String type=path.endsWith(".js")?"text/javascript":path.endsWith(".css")?"text/css":path.endsWith(".png")?"image/png":path.endsWith(".svg")?"image/svg+xml":"text/html";exchange.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,data.length);try(var out=exchange.getResponseBody()){out.write(data);}
            }
        }catch(Exception error){try{send(exchange,400,Map.of("error",error.getMessage()==null?"Storage selection failed":error.getMessage()));}catch(Exception ignored){exchange.close();}}});
        server.start();System.out.println("Storage setup: open http://SERVER:"+port+"/#/storage-setup");System.out.println("First-install authorization token: "+token);
        try{configured.await();}finally{server.stop(1);workers.shutdownNow();}
    }
    private static void send(HttpExchange exchange,int status,Object value)throws Exception{byte[] data=JSON.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,data.length);try(var out=exchange.getResponseBody()){out.write(data);}}
}
