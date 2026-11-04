package com.chatchat.api.model;

import com.chatchat.api.controller.mcp.McpControlPlaneClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformInternetPolicyTest {
    @Test void persistsMultipleChoicesAndMigratesLegacySingleChoiceWithoutRevivingOldRevision() throws Exception {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        var mcp=mock(McpControlPlaneClient.class);var policy=new PlatformInternetPolicy(jdbc,mcp);policy.initialize();
        when(mcp.request("GET","/api/v1/internet-search/providers",null)).thenReturn(new ObjectMapper().readTree("[{\"id\":\"a\",\"enabled\":true,\"configured\":true},{\"id\":\"b\",\"enabled\":true,\"configured\":true}]"));
        jdbc.update("INSERT INTO platform_model_internet_policy VALUES ('legacy',TRUE,'a',7)");
        assertThat(policy.read("legacy").providerIds()).containsExactly("a");
        policy.save("legacy",true,null,List.of("a","b"));
        var reopened=new PlatformInternetPolicy(jdbc,mcp);reopened.initialize();
        assertThat(reopened.read("legacy").providerIds()).containsExactly("a","b");
        assertThat(reopened.read("legacy").revision()).isEqualTo(8);
        policy.save("legacy",true,null,List.of("a","b"));assertThat(policy.read("legacy").revision()).isEqualTo(8);
        assertThatThrownBy(()->policy.save("legacy",true,null,List.of("unconfigured"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(policy.read("legacy").providerIds()).containsExactly("a","b");
        policy.save("legacy",false,null,List.of("a","b"));assertThat(policy.read("legacy").revision()).isEqualTo(9);
        policy.save("legacy",true,null,List.of("b"));assertThat(policy.read("legacy").revision()).isEqualTo(10);
        assertThat(policy.read("legacy").providerIds()).containsExactly("b");
    }
}
