package com.chatchat.deployment.api;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.chatchat.deployment.monitoring.ServiceHealthService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"ADMIN_PASSWORD=platform-test-password","ops.monitor.interval=600000"})
class PlatformServicesIntegrationTest {
    static final Path directory;
    static { try { directory=Files.createTempDirectory("ops-platform-test-"); } catch(Exception error){throw new RuntimeException(error);} }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("data/deployment-ops").toString().replace('\\','/')+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        registry.add("OPS_DATA_DIR",()->directory.resolve("data").toString());
    }
    @Autowired TestRestTemplate client;
    @Autowired ServiceHealthService health;
    @Autowired JdbcTemplate db;
    HttpHeaders headers;
    @BeforeEach void login(){
        var login=client.postForEntity("/api/login",Map.of("username","admin","password","platform-test-password"),Map.class);
        assertEquals(200,login.getStatusCode().value());headers=new HttpHeaders();headers.set(HttpHeaders.COOKIE,login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0]);headers.set("X-Ops-Request","1");
    }
    @Test void realEndpointFailureRecoveryIsPersisted()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var status=new java.util.concurrent.atomic.AtomicInteger(503);
        server.createContext("/health",exchange->{exchange.sendResponseHeaders(status.get(),-1);exchange.close();});server.start();
        try{
            var service=Map.of("name","MCP health acceptance","kind","mcp","clusterName","acceptance","url","http://127.0.0.1:"+server.getAddress().getPort()+"/health");
            assertEquals(200,client.postForEntity("/api/services",new HttpEntity<>(service,headers),Object.class).getStatusCode().value());
            health.sample();assertEquals("UNHEALTHY",db.queryForObject("SELECT status FROM service_checks WHERE name='MCP health acceptance'",String.class));
            status.set(200);health.sample();assertEquals("HEALTHY",db.queryForObject("SELECT status FROM service_checks WHERE name='MCP health acceptance'",String.class));
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM service_alerts a JOIN service_checks s ON s.id=a.service_id WHERE s.name='MCP health acceptance'",Integer.class));
        }finally{server.stop(0);}
    }
    @Test void firstUnreachableSampleIsRecordedWithoutRepeatingUnchangedAlerts()throws Exception{
        int port;
        try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        String name="Unreachable-"+UUID.randomUUID();
        health.save(Map.of("name",name,"kind","tcp","url","tcp://127.0.0.1:"+port));
        health.sample();
        assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM service_checks WHERE name=?",String.class,name));
        assertNotNull(db.queryForObject("SELECT checked_at FROM service_checks WHERE name=?",Object.class,name));
        health.sample();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM service_alerts a JOIN service_checks s ON s.id=a.service_id WHERE s.name=?",Integer.class,name));
    }
    @Test void consistentBackupContainsRestorableManagementRecords()throws Exception{
        String name="Backup-entry-"+UUID.randomUUID();
        client.postForEntity("/api/entries",new HttpEntity<>(Map.of("name",name,"url","http://127.0.0.1:19300"),headers),Object.class);
        var response=client.postForEntity("/api/backups",new HttpEntity<>(Map.of(),headers),Map.class);assertEquals(200,response.getStatusCode().value());
        Path archive=directory.resolve("backups").resolve(response.getBody().get("name").toString()),restored=Files.createTempDirectory(directory,"restored-");
        try(var zip=new ZipFile(archive.toFile());var database=new ZipInputStream(zip.getInputStream(zip.getEntry("database.zip")))){
            var entry=database.getNextEntry();assertEquals("deployment-ops.mv.db",entry.getName());Files.copy(database,restored.resolve(entry.getName()));
        }
        try(var connection=DriverManager.getConnection("jdbc:h2:file:"+restored.resolve("deployment-ops").toString().replace('\\','/')+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE","deployment_ops","");var query=connection.prepareStatement("SELECT count(*) FROM entries WHERE name=?")){
            query.setString(1,name);var rows=query.executeQuery();rows.next();assertEquals(1,rows.getInt(1));
        }
    }
    @Test void readOnlyAccountCannotMaintainPlatform() {
        client.postForEntity("/api/users",new HttpEntity<>(Map.of("username","platform-viewer","password","viewer-password","role","viewer"),headers),Object.class);
        var login=client.postForEntity("/api/login",Map.of("username","platform-viewer","password","viewer-password"),Map.class);
        var viewer=new HttpHeaders();viewer.set(HttpHeaders.COOKIE,login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0]);viewer.set("X-Ops-Request","1");
        assertEquals(403,client.postForEntity("/api/backups",new HttpEntity<>(Map.of(),viewer),Map.class).getStatusCode().value());
        assertEquals(403,client.postForEntity("/api/services",new HttpEntity<>(Map.of(),viewer),Map.class).getStatusCode().value());
        assertEquals(403,client.exchange("/api/backups",HttpMethod.GET,new HttpEntity<>(viewer),Map.class).getStatusCode().value());
    }
    @Test void configurationUpdatesPersistMonotonicRevision(){
        for(String name:List.of("Acceptance organization","Updated organization"))assertEquals(200,client.postForEntity("/api/configuration",new HttpEntity<>(Map.of("key","organization.name","value",name),headers),Object.class).getStatusCode().value());
        assertEquals("Updated organization",db.queryForObject("SELECT setting_value FROM platform_settings WHERE setting_key='organization.name'",String.class));
        assertEquals(2,db.queryForObject("SELECT revision FROM platform_settings WHERE setting_key='organization.name'",Integer.class));
    }
}
