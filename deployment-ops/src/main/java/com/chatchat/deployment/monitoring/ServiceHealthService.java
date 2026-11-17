package com.chatchat.deployment.monitoring;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class ServiceHealthService {
    private final JdbcTemplate db;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ServiceHealthService(JdbcTemplate db){this.db=db;}
    public List<Map<String,Object>> list(){return db.queryForList("SELECT * FROM service_checks ORDER BY cluster_name,name,id");}
    public synchronized void save(Map<String,Object> spec){
        String name=String.valueOf(spec.getOrDefault("name","")),kind=String.valueOf(spec.getOrDefault("kind","http"));
        if(name.isBlank()||name.length()>128||!Set.of("runtime-os","coding-agent","mcp","python","dolphinscheduler","http","tcp").contains(kind))throw new IllegalArgumentException("Invalid service name or kind");
        URI url=URI.create(String.valueOf(spec.get("url")));
        if(!Set.of("http","https","tcp").contains(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null||url.getFragment()!=null)throw new IllegalArgumentException("HTTP(S) or TCP endpoint without embedded credentials required");
        if(url.getScheme().equals("tcp")&&(url.getPort()<1||url.getPort()>65535))throw new IllegalArgumentException("TCP port required");
        int status=Integer.parseInt(String.valueOf(spec.getOrDefault("expectedStatus",200)));
        if(status<100||status>599)throw new IllegalArgumentException("Invalid expected HTTP status");
        String cluster=String.valueOf(spec.getOrDefault("clusterName",""));if(cluster.length()>128)throw new IllegalArgumentException("Cluster name too long");
        if(spec.get("id") instanceof Number id){
            if(db.update("UPDATE service_checks SET name=?,cluster_name=?,kind=?,url=?,expected_status=?,enabled=?,status='UNKNOWN',checked_at=NULL WHERE id=?",name,cluster,kind,url.toString(),status,!Boolean.FALSE.equals(spec.get("enabled")),id.longValue())!=1)throw new IllegalArgumentException("Service not found");
        }else{
            if(db.queryForObject("SELECT COUNT(*) FROM service_checks",Integer.class)>=100)throw new IllegalArgumentException("Service limit reached");
            db.update("INSERT INTO service_checks(name,cluster_name,kind,url,expected_status,enabled) VALUES(?,?,?,?,?,?)",name,cluster,kind,url.toString(),status,!Boolean.FALSE.equals(spec.get("enabled")));
        }
    }
    @Scheduled(fixedDelayString="${ops.monitor.interval:30000}",initialDelay=5000)
    public synchronized void sample(){
        for(var item:db.queryForList("SELECT * FROM service_checks WHERE enabled=TRUE ORDER BY id")){
            String status="HEALTHY",message="Endpoint responded as configured";long start=System.nanoTime();
            try{
                URI url=URI.create(item.get("url").toString());
                if(url.getScheme().equals("tcp")){try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(url.getHost(),url.getPort()),3000);}}
                else{var response=http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.discarding());int expected=((Number)item.get("expected_status")).intValue();if(response.statusCode()!=expected){status="UNHEALTHY";message="HTTP "+response.statusCode()+"; expected "+expected;}}
            }catch(Exception error){status="UNKNOWN";message=error.getClass().getSimpleName()+": endpoint unavailable";if(error instanceof InterruptedException)Thread.currentThread().interrupt();}
            long latency=(System.nanoTime()-start)/1_000_000;
            db.update("UPDATE service_checks SET status=?,message=?,latency_ms=?,checked_at=CURRENT_TIMESTAMP WHERE id=?",status,message,latency,item.get("id"));
            if(item.get("checked_at")==null||!status.equals(item.get("status")))db.update("INSERT INTO service_alerts(service_id,status,message) VALUES(?,?,?)",item.get("id"),status,message);
        }
        db.update("DELETE FROM service_alerts WHERE created_at < ?",OffsetDateTime.now(ZoneOffset.UTC).minusDays(30));
    }
    public List<Map<String,Object>> alerts(){return db.queryForList("SELECT a.*,s.name,s.cluster_name,s.kind,s.url FROM service_alerts a JOIN service_checks s ON a.service_id=s.id ORDER BY a.id DESC LIMIT 500");}
}
