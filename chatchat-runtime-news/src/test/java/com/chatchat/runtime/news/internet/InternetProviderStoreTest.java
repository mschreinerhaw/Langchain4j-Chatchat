package com.chatchat.runtime.news.internet;

import com.chatchat.common.security.*;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class InternetProviderStoreTest {
    InternetProviderStore store;JdbcTemplate jdbc;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(ds);
        var secrets=new InternalCredentialProperties();secrets.setCryptoKey("test-only-key");
        store=new InternetProviderStore(jdbc,secrets,new ObjectMapper(),new NewsRuntimeProperties(),new DataSourceTransactionManager(ds));store.initialize();
    }
    InternetProviderStore.Provider configured(){
        store.save(new InternetProviderStore.Draft("test","TAVILY","Test","https://api.tavily.com/search","test-secret",null,true,1000,30,1,100));return store.find("test");
    }
    @Test void encryptsAtRestAndNeverExposesSecretsInViewsOrToString(){
        var p=configured();assertThat(p.credentialCipher()).startsWith("ENC(").doesNotContain("test-secret");
        assertThat(store.credentials(p)).containsEntry("apiKey","test-secret");
        assertThat(store.views().toString()).doesNotContain("test-secret","credentialCipher").contains("endpoint=https://api.tavily.com/search");assertThat(p.toString()).doesNotContain(p.credentialCipher());
        assertThat(new InternetProviderStore.Draft("x","TAVILY","X","https://example.com","secret",null,true,null,null,null,null).toString()).doesNotContain("secret,");
        jdbc.update("UPDATE internet_search_provider SET credential_cipher=? WHERE id='test'","{\"apiKey\":\"plaintext\"}");
        assertThatThrownBy(()->store.credentials(store.find("test"))).hasMessage("PROVIDER_CREDENTIAL_UNAVAILABLE");
    }
    @Test void dynamicEntriesRetainCredentialsAndDeletionNeverResetsRevision(){
        assertThat(store.views()).isEmpty();
        var saved=store.save(new InternetProviderStore.Draft(null,"TAVILY","Custom search","https://api.tavily.com/search","test-secret",null,true,null,null,null,null));
        String id=(String)saved.get("id");assertThat(id).startsWith("search-");
        store.save(new InternetProviderStore.Draft(id,"TAVILY","Renamed search","https://api.tavily.com/search","",null,false,null,null,null,null));
        assertThat(store.credentials(store.find(id))).containsEntry("apiKey","test-secret");
        long revision=store.find(id).revision();store.delete(id);store.initialize();
        assertThat(store.views()).isEmpty();assertThat(store.find(id).credentialCipher()).isNull();
        store.save(new InternetProviderStore.Draft(id,"TAVILY","Recreated","https://api.tavily.com/search","new-secret",null,true,null,null,null,null));
        assertThat(store.find(id).revision()).isGreaterThan(revision+1);
    }
    @Test void taskQuotaFailureRollsBackOtherBuckets(){
        var p=configured();store.reserve(p,"task-a");
        assertThatThrownBy(()->store.reserve(p,"task-a")).hasMessage("TASK_SEARCH_QUOTA");
        assertThat(jdbc.queryForObject("SELECT MAX(calls_used) FROM internet_search_quota",Integer.class)).isEqualTo(1);
        store.reserve(p,"task-b");assertThat(jdbc.queryForObject("SELECT MAX(calls_used) FROM internet_search_quota",Integer.class)).isEqualTo(2);
    }
    @Test void durableIdempotencyDoesNotRedispatchUnknownOrCompletedCalls(){
        var p=configured();assertThat(store.begin("r","t","tenant","user","agent",p.id(),"web_search","h")).isTrue();
        assertThat(store.begin("r","t","tenant","user","agent",p.id(),"web_search","h")).isFalse();
        assertThat(store.replay("r")).containsEntry("errorCode","REMOTE_OUTCOME_UNKNOWN");
        store.finish("r",p,Map.of("status","OK","count",0),12);
        assertThat(store.replay("r")).containsEntry("status","OK");
        assertThatThrownBy(()->store.begin("r","t","other","user","agent",p.id(),"web_search","h")).isInstanceOf(SecurityException.class);
    }
    @Test void changingKindCannotRetainWrongCredentials(){
        configured();assertThatThrownBy(()->store.save(new InternetProviderStore.Draft("test","BRAVE","Test","https://api.search.brave.com/res/v1/web/search","",null,true,1000,30,1,100))).isInstanceOf(IllegalArgumentException.class);
    }
}
