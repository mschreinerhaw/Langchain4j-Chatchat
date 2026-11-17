package com.chatchat.deployment.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:native-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "ADMIN_PASSWORD=native-test-password"})
class NativeApiIntegrationTest {
    @Autowired TestRestTemplate client;
    @Test void realHttpLoginAuditAndPermissions() {
        assertEquals(401,client.getForEntity("/api/me",Map.class).getStatusCode().value());
        var login=client.postForEntity("/api/login",Map.of("username","admin","password","native-test-password"),Map.class);
        assertEquals(200,login.getStatusCode().value());
        String cookie=login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
        HttpHeaders headers=new HttpHeaders();headers.set(HttpHeaders.COOKIE,cookie);headers.set("X-Ops-Request","1");
        var request=new HttpEntity<>(Map.of("name","Runtime entry","url","http://127.0.0.1:19300"),headers);
        assertEquals(200,client.postForEntity("/api/entries",request,Object.class).getStatusCode().value());
        assertEquals(200,client.exchange("/api/audit",HttpMethod.GET,new HttpEntity<>(headers),Object.class).getStatusCode().value());
        headers.remove("X-Ops-Request");
        assertEquals(403,client.postForEntity("/api/entries",new HttpEntity<>(request.getBody(),headers),Object.class).getStatusCode().value());
        assertEquals(200,client.getForEntity("/api/health",Map.class).getStatusCode().value());
    }
}
