package com.chatchat.deployment.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:first-install;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
class FirstInstallIntegrationTest {
    @Autowired TestRestTemplate client;
    @Test void administratorIsCreatedOnceWithLocalAuthorizationAndUserPassword(){
        assertEquals(true,client.getForObject("/api/setup",Map.class).get("required"));
        System.setProperty("OPS_SETUP_TOKEN","test-local-install-authorization");
        try{
            var headers=new HttpHeaders();headers.set("X-Ops-Request","1");
            var wrong=new HttpEntity<>(Map.of("token","wrong","password","user-selected-password"),headers);
            assertEquals(403,client.postForEntity("/api/setup",wrong,Map.class).getStatusCode().value());
            var valid=new HttpEntity<>(Map.of("token","test-local-install-authorization","password","user-selected-password"),headers);
            assertEquals(200,client.postForEntity("/api/setup",valid,Map.class).getStatusCode().value());
            assertEquals(409,client.postForEntity("/api/setup",valid,Map.class).getStatusCode().value());
            assertEquals(false,client.getForObject("/api/setup",Map.class).get("required"));
            assertEquals(200,client.postForEntity("/api/login",Map.of("username","admin","password","user-selected-password"),Map.class).getStatusCode().value());
        }finally{System.clearProperty("OPS_SETUP_TOKEN");}
    }
}
