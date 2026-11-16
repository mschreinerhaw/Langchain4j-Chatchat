package com.chatchat.deployment.api;

import com.sun.net.httpserver.HttpExchange;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatchat.deployment.backup.BackupService;
import com.chatchat.deployment.monitoring.ServiceHealthService;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Authenticated extension routes, invoked after the shared session/role/CSRF checks. */
@Component
public class NativeManagementRoutes {
    private final JdbcTemplate db;private final ObjectMapper json;private final BackupService backups;private final ServiceHealthService health;private final Environment env;private final com.chatchat.deployment.backup.UpgradeService upgrades;
    public NativeManagementRoutes(JdbcTemplate db,ObjectMapper json,BackupService backups,ServiceHealthService health,Environment env,com.chatchat.deployment.backup.UpgradeService upgrades){this.db=db;this.json=json;this.backups=backups;this.health=health;this.env=env;this.upgrades=upgrades;}
    private Map<String,Object> body(HttpExchange x)throws Exception{byte[] bytes=x.getRequestBody().readNBytes(65537);if(bytes.length>65536)throw new IllegalArgumentException("Request too large");return json.readValue(bytes,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
    private void send(HttpExchange x,Object value)throws Exception{byte[] data=json.writeValueAsBytes(value);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);x.close();}
    private void audit(String actor,String action,String target,String outcome){db.update("INSERT INTO audit(actor,action,target,outcome) VALUES(?,?,?,?)",actor,action,target,outcome);}
    public boolean route(HttpExchange x,String actor,String role)throws Exception{
        String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
        if(!path.matches("/api/(platform(?:/.*)?|services|alerts|backups(?:/.*)?|configuration)"))return false;
        if(!Set.of("GET","POST").contains(method))throw new IllegalArgumentException("Unsupported method");
        if(path.startsWith("/api/backups")&&!role.equals("admin"))throw new IllegalArgumentException("Administrator required");
        boolean write=method.equals("POST");Object value;
        if(write)audit(actor,method,path,"requested");
        try{
            if(path.equals("/api/platform/health")&&!write){
                boolean mounted=false;try{mounted=Files.readAllLines(Path.of("/proc/self/mountinfo")).stream().anyMatch(line->line.split(" ")[4].equals("/mnt/disk01"));}catch(Exception ignored){}
                db.queryForObject("SELECT 1",Integer.class);
                value=Map.of("platform","UP","database","UP","databaseType","H2 embedded","requiredMount","/mnt/disk01","mounted",mounted,"schemaVersion",db.queryForObject("SELECT MAX(version) FROM schema_version",Integer.class),"dataDirectory",env.getProperty("OPS_DATA_DIR","/mnt/disk01/deployment-ops/data"));
            }else if(path.equals("/api/services")){if(write)health.save(body(x));value=health.list();}
            else if(path.equals("/api/alerts")&&!write)value=health.alerts();
            else if(path.equals("/api/backups"))value=write?backups.create():backups.list();
            else if(path.equals("/api/platform/upgrades")&&write)value=upgrades.receive(x.getRequestBody(),Long.parseLong(x.getRequestHeaders().getFirst("Content-Length")),x.getRequestHeaders().getFirst("X-SHA256"));
            else if(path.matches("/api/backups/backup-[a-f0-9-]{36}\\.zip/download")&&!write){
                Path file=backups.file(path.split("/")[3]);audit(actor,"backup-download",file.getFileName().toString(),"requested");x.getResponseHeaders().set("Content-Type","application/zip");x.getResponseHeaders().set("Content-Disposition","attachment; filename="+file.getFileName());x.sendResponseHeaders(200,Files.size(file));Files.copy(file,x.getResponseBody());x.close();return true;
            }else if(path.equals("/api/platform/maintenance")&&write){
                Map<String,Object> request=body(x);String action=String.valueOf(request.get("action"));
                if(!Set.of("restore","upgrade").contains(action))throw new IllegalArgumentException("Unsupported platform operation");
                String target=String.valueOf(request.get("name"));if(!String.valueOf(request.get("confirmation")).equals(action.toUpperCase(Locale.ROOT)+":"+target))throw new IllegalArgumentException("Exact maintenance scope confirmation required");
                var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
                var response=client.send(HttpRequest.newBuilder(URI.create(env.getProperty("OPS_EXECUTOR_URL","http://127.0.0.1:19242")+"/platform/maintenance")).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+env.getProperty("EXECUTOR_TOKEN","")).header("X-Ops-Actor",actor).POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(request))).build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()!=200)throw new IllegalArgumentException(response.body());value=json.readValue(response.body(),Object.class);
            }else if(path.equals("/api/configuration")){
                if(write){Map<String,Object> spec=body(x);String key=String.valueOf(spec.get("key")),content=String.valueOf(spec.get("value"));
                    if(!Set.of("organization.name","monitoring.description").contains(key)||content.length()>1024)throw new IllegalArgumentException("Unsupported configuration key/value");
                    db.update("MERGE INTO platform_settings(setting_key,setting_value,revision,updated_at) KEY(setting_key) VALUES(?,?,COALESCE((SELECT revision+1 FROM platform_settings WHERE setting_key=?),1),CURRENT_TIMESTAMP)",key,content,key);
                }value=db.queryForList("SELECT * FROM platform_settings ORDER BY setting_key");
            }else return false;
            if(write)audit(actor,method,path,"success");send(x,value);return true;
        }catch(Exception error){if(write)audit(actor,method,path,"failed");throw error;}
    }
}
