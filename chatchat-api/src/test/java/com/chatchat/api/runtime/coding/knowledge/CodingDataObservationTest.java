package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.api.controller.mcp.McpControlPlaneClient;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingDataObservationTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @Test @EnabledIfSystemProperty(named="runtime.empty.census",matches=".+")
    void sourceEmptyUsesActualStorageCoverageRatherThanAZeroArray() throws Exception {
        var fixture=json.readTree(Files.readAllBytes(Path.of(System.getProperty("runtime.empty.census"))));
        Map<String,Object> body=json.convertValue(fixture.path("response"),Map.class),query=json.convertValue(fixture.path("query"),Map.class);
        var result=CodingDataObservation.describe(contract(),"market_get_history",query,body,null,Instant.now(),1);
        assertThat(result).containsEntry("status","SOURCE_EMPTY").containsEntry("sourceEmptyProven",true);
        var slice=new LinkedHashMap<>(body);slice.remove("source_coverage");
        assertThat(CodingDataObservation.describe(contract(),"market_get_history",query,slice,null,Instant.now(),1))
            .containsEntry("status","EMPTY_RESULT").containsEntry("sourceEmptyProven",false);
        var wrong=new LinkedHashMap<>(query);wrong.put("dataset","other");
        assertThat(CodingDataObservation.describe(contract(),"market_get_history",wrong,body,null,Instant.now(),1))
            .doesNotContainEntry("status","SOURCE_EMPTY");
    }
    @Test void anErrorEnvelopeWithZeroCountAndEmptyItemsIsStillAnError() {
        var result=CodingDataObservation.describe(contract(),"web_search",Map.of("query","public source"),
            Map.of("status","ERROR","errorCode","SEARCH_SERVICE_UNAVAILABLE","items",List.of(),"count",0),null,Instant.now(),1);
        assertThat(result).containsEntry("status","SOURCE_UNAVAILABLE").containsEntry("returnedRowCount",null);
        assertThat(result.get("conditions")).asList().containsExactly("SOURCE_UNAVAILABLE");
    }
    CodingTaskContract contract() {
        var c=mock(CodingTaskContract.class);
        when(c.runtimeContext()).thenReturn(Map.of("internetAvailability",Map.of("status","UNAVAILABLE","reasonCode","MODEL_INTERNET_DISABLED","serviceChecked",false)));
        when(c.authorization()).thenReturn(new CodingTaskContract.Authorization("decision",
            List.of("market_get_history","market_get_day_snapshot","market_search"),List.of(),List.of(),List.of(),
            CodingTaskContract.NetworkPolicy.NONE,List.of(),List.of()));return c;
    }
    @ParameterizedTest @ValueSource(ints={503,403})
    void actualHttpFailureRetainsStatusAndNeverBecomesAnEmptyArray(int status) throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();boolean login=exchange.getRequestURI().getPath().endsWith("/auth/login");
            byte[] body=(login?"{\"code\":200,\"data\":{\"token\":\"isolated-test-token\",\"expiresAt\":"+(System.currentTimeMillis()+60000)+"}}"
                :"{\"code\":"+status+",\"message\":\"controlled source failure\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(login?200:status,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var credential=new InternalCredentialProperties();credential.setCryptoKey("isolated-test-key");
            credential.setEncryptedSecret(InternalSecretCipher.encrypt("isolated-test-secret","isolated-test-key"));
            var env=new MockEnvironment().withProperty("chatchat.mcp.center.base-url","http://127.0.0.1:"+server.getAddress().getPort());
            var client=new McpControlPlaneClient(env,credential,json);var reader=new CodingResourceReader(client,json);
            var failure=catchThrowableOfType(()->reader.read("market_get_history",json.valueToTree(Map.of("dataset","quotes"))),ResponseStatusException.class);
            assertThat(failure.getStatusCode().value()).isEqualTo(status);
            var result=CodingDataObservation.describe(contract(),"market_get_history",Map.of("dataset","quotes"),Map.of(),failure,Instant.now(),1);
            assertThat(result).containsEntry("status",status==403?"ACCESS_DENIED":"SOURCE_UNAVAILABLE")
                .containsEntry("returnedRowCount",null).containsEntry("httpStatus",status);
            assertThat(result.get("availableTools")).asList().doesNotContain("web_search","web_fetch");
        }finally {server.stop(0);}
    }
    @Test @EnabledIfSystemProperty(named="runtime.empty.sources",matches=".+")
    void replaysUnmodifiedRealFinancialReadsWithCapturedHashesAndClassifiesEachObservation() throws Exception {
        var path=Path.of(System.getProperty("runtime.empty.sources"));byte[] raw=Files.readAllBytes(path);
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)))
            .isEqualTo(System.getProperty("runtime.empty.sources.sha256"));
        var fixtures=json.readTree(raw);var observations=new ArrayList<Map<String,Object>>();
        for(var f:fixtures) {
            Map<String,Object> body=json.convertValue(f.path("response"),Map.class),query=json.convertValue(f.path("query"),Map.class);
            String before=json.writeValueAsString(body);
            var result=CodingDataObservation.describe(contract(),f.path("tool").asText(),query,body,null,
                Instant.parse(f.path("startedAt").asText()),f.path("elapsedMs").asLong());
            String id=f.path("case").asText();String expected=switch(id){case "E01"->"EMPTY_RESULT";case "E02"->"AVAILABLE";case "E04"->"DATE_MISMATCH";case "E05"->"FIELD_MISSING";default->throw new AssertionError(id);};
            if(id.equals("E05")) {
                List<Map<String,Object>> rows=json.convertValue(f.path("response").path("items"),new com.fasterxml.jackson.core.type.TypeReference<>(){});
                var fields=CodingDataStatus.describe(rows,true,List.of("official_listing_date"),query);result.put("requiredFieldObservation",fields);
                assertThat(fields).containsEntry("status",expected).containsEntry("missingFields",List.of("official_listing_date"));
            } else assertThat(result).containsEntry("status",expected);
            assertThat(result.get("query")).isEqualTo(query);assertThat(result.get("sourceEmptyProven")).isEqualTo(false);
            assertThat(json.writeValueAsString(body)).isEqualTo(before); // Delivery metadata never changes source hashes.
            result.put("case",id);observations.add(result);
        }
        assertThat(observations).hasSize(4);
        if(System.getProperty("runtime.empty.output")!=null)Files.writeString(Path.of(System.getProperty("runtime.empty.output")),json.writerWithDefaultPrettyPrinter().writeValueAsString(observations));
    }
}
