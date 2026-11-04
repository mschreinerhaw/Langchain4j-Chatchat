package com.chatchat.runtime.news.internet;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.search.provider.TencentWebSearchClient;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fault injection tests; these do not count as live Provider acceptance. */
class InternetSearchServiceTest {
    final ObjectMapper json=new ObjectMapper();InternetProviderStore store;InternetSearchService search;
    int status=200,calls;String response="{\"results\":[]}";String error;
    @BeforeEach void configure(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var secrets=new InternalCredentialProperties();secrets.setCryptoKey("test-key");
        store=new InternetProviderStore(new JdbcTemplate(ds),secrets,json,new NewsRuntimeProperties(),new DataSourceTransactionManager(ds));store.initialize();
        store.save(new InternetProviderStore.Draft("test","TAVILY","Test","https://api.tavily.com/search","private-api-key",null,true,1000,30,10,100));
        var http=new PublicWebHttp(){@Override public Response request(URI uri,String method,Map<String,String> headers,byte[] body,long deadline){
            calls++;if(error!=null)throw new Rejected(error);return new Response(uri,status,"application/json","https://other.example/",response.getBytes(StandardCharsets.UTF_8));
        }};
        search=new InternetSearchService(store,http,new RobotsPolicy(http),json,mock(TencentWebSearchClient.class));
    }
    JsonNode request(String id){return json.valueToTree(Map.of("providerId","test","providerRevision",1,"taskId","task","tenantId","tenant","userId","user","agentId","agent","tool","web_search","requestId",id,"arguments",Map.of("query","public question","count",3)));}
    @Test void emptyResultsRemainExplicitlyEmptyAndReplayDoesNotCallAgain(){
        var result=search.execute(request("empty"));assertThat(result).containsEntry("status","OK").containsEntry("count",0);
        assertThat(search.execute(request("empty"))).isEqualTo(result);assertThat(calls).isEqualTo(1);
    }
    @Test void authRateTimeoutAndRedirectFailuresNeverForwardResponseBodies(){
        response="private-api-key raw diagnostic";
        for(var entry:Map.of(401,"PROVIDER_AUTH_FAILED",403,"PROVIDER_AUTH_FAILED",429,"PROVIDER_RATE_LIMITED",302,"PROVIDER_HTTP_ERROR",500,"PROVIDER_HTTP_ERROR").entrySet()){
            status=entry.getKey();var result=search.execute(request("status-"+status));assertThat(result).containsEntry("errorCode",entry.getValue());assertThat(result.toString()).doesNotContain("private-api-key","raw diagnostic");
        }
        error="HTTP_TIMEOUT";assertThat(search.execute(request("timeout"))).containsEntry("errorCode","HTTP_TIMEOUT");
    }
    @Test void stripsHtmlAndRedactsReflectedCredentialsAcrossSourceFields(){
        response="{\"results\":[{\"title\":\"private-api-key\",\"url\":\"https://example.com/a\",\"content\":\"<script>ignored</script><p>actual text</p>\",\"published_date\":\"private-api-key\"}]}";
        var result=search.execute(request("reflected"));assertThat(result).containsEntry("count",1);
        assertThat(result.toString()).doesNotContain("private-api-key","<script>","ignored").contains("actual text","REDACTED","SEARCH_SNIPPETS_NOT_FULL_PAGES");
    }
    @Test void disabledAndChangedProviderRejectBeforeOutboundCall(){
        var request=(com.fasterxml.jackson.databind.node.ObjectNode)request("changed");request.put("providerRevision",2);
        assertThat(search.execute(request)).containsEntry("errorCode","PROVIDER_CONFIGURATION_CHANGED");assertThat(calls).isZero();
        store.save(new InternetProviderStore.Draft("test","TAVILY","Test","https://api.tavily.com/search",null,null,false,1000,30,10,100));
        assertThat(search.execute(request("disabled"))).containsEntry("errorCode","PROVIDER_DISABLED");assertThat(calls).isZero();
    }
}
