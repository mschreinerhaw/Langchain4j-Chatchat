package com.chatchat.runtime.news.internet;

import com.chatchat.common.security.*;
import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.DuplicateKeyException;
import java.util.*;

/** Central server-side provider configuration and durable attempted-call quotas. */
@Service
public class InternetProviderStore {
    private final JdbcTemplate jdbc;private final InternalCredentialProperties secrets;private final ObjectMapper json;
    private final NewsRuntimeProperties legacy;private final TransactionTemplate transactions;
    public InternetProviderStore(JdbcTemplate jdbc,InternalCredentialProperties secrets,ObjectMapper json,NewsRuntimeProperties legacy,PlatformTransactionManager tx){
        this.jdbc=jdbc;this.secrets=secrets;this.json=json;this.legacy=legacy;transactions=new TransactionTemplate(tx);
    }
    public record Provider(String id,String kind,String name,String endpoint,String credentialCipher,boolean enabled,
        int timeoutMillis,int requestsPerMinute,int maxTaskCalls,int maxDailyCalls,long revision,String lastStatus){
        @Override public String toString(){return "Provider[id="+id+", kind="+kind+", revision="+revision+"]";}
    }
    public record Draft(String id,String kind,String name,String endpoint,String apiKey,String secretId,Boolean enabled,
        Integer timeoutMillis,Integer requestsPerMinute,Integer maxTaskCalls,Integer maxDailyCalls){
        @Override public String toString(){return "ProviderDraft[id="+id+", kind="+kind+", credentials=REDACTED]";}
    }
    @PostConstruct public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS internet_search_provider(id VARCHAR(64) PRIMARY KEY,kind VARCHAR(24) NOT NULL,name VARCHAR(128) NOT NULL,endpoint VARCHAR(1024) NOT NULL,credential_cipher TEXT,enabled BOOLEAN NOT NULL,timeout_ms INTEGER NOT NULL,rpm INTEGER NOT NULL,max_task_calls INTEGER NOT NULL,max_daily_calls INTEGER NOT NULL,revision BIGINT NOT NULL,last_status VARCHAR(64))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS internet_search_quota(bucket_id VARCHAR(256) PRIMARY KEY,calls_used INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS internet_search_request(request_id VARCHAR(128) PRIMARY KEY,task_id VARCHAR(256) NOT NULL,tenant_id VARCHAR(128) NOT NULL,user_id VARCHAR(128) NOT NULL,agent_id VARCHAR(256) NOT NULL,provider_id VARCHAR(64) NOT NULL,tool_name VARCHAR(32) NOT NULL,request_hash VARCHAR(64) NOT NULL,started_at BIGINT NOT NULL,elapsed_ms BIGINT,result_count INTEGER,response_bytes INTEGER,status VARCHAR(32) NOT NULL,error_code VARCHAR(64),result_json TEXT)");
        if(find("tencent-wsa")==null){
            var c=legacy.getWebSearch();String cipher=null;
            if(c.getSecretKey()!=null&&!c.getSecretKey().isBlank()&&c.getSecretId()!=null&&!c.getSecretId().isBlank()&&!cryptoKey().isBlank())
                cipher=encrypt(Map.of("apiKey",c.getSecretKey(),"secretId",c.getSecretId()));
            insert(new Provider("tencent-wsa","TENCENT","腾讯 WSA",c.getEndpoint(),cipher,c.isEnabled(),10000,30,12,1000,1,"NOT_TESTED"));
        }
        if(find("brave")==null)insert(new Provider("brave","BRAVE","Brave Search","https://api.search.brave.com/res/v1/web/search",null,false,10000,30,12,1000,1,"NOT_CONFIGURED"));
        if(find("tavily")==null)insert(new Provider("tavily","TAVILY","Tavily Search","https://api.tavily.com/search",null,false,10000,30,12,1000,1,"NOT_CONFIGURED"));
    }
    private void insert(Provider p){jdbc.update("INSERT INTO internet_search_provider(id,kind,name,endpoint,credential_cipher,enabled,timeout_ms,rpm,max_task_calls,max_daily_calls,revision,last_status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",p.id(),p.kind(),p.name(),p.endpoint(),p.credentialCipher(),p.enabled(),p.timeoutMillis(),p.requestsPerMinute(),p.maxTaskCalls(),p.maxDailyCalls(),p.revision(),p.lastStatus());}
    public Provider find(String id){return jdbc.query("SELECT * FROM internet_search_provider WHERE id=?",rs->rs.next()?new Provider(rs.getString("id"),rs.getString("kind"),rs.getString("name"),rs.getString("endpoint"),rs.getString("credential_cipher"),rs.getBoolean("enabled"),rs.getInt("timeout_ms"),rs.getInt("rpm"),rs.getInt("max_task_calls"),rs.getInt("max_daily_calls"),rs.getLong("revision"),rs.getString("last_status")):null,id);}
    public List<Map<String,Object>> views(){return jdbc.queryForList("SELECT id FROM internet_search_provider ORDER BY name").stream().map(row->view(find((String)row.get("id")))).toList();}
    public Map<String,Object> view(Provider p){return Map.ofEntries(Map.entry("id",p.id()),Map.entry("name",p.name()),Map.entry("kind",p.kind()),Map.entry("enabled",p.enabled()),Map.entry("configured",p.credentialCipher()!=null),Map.entry("revision",p.revision()),Map.entry("status",Objects.toString(p.lastStatus(),"NOT_TESTED")),Map.entry("supportsNews",!p.kind().equals("TENCENT")),Map.entry("timeoutMillis",p.timeoutMillis()),Map.entry("maxTaskCalls",p.maxTaskCalls()),Map.entry("requestsPerMinute",p.requestsPerMinute()),Map.entry("maxDailyCalls",p.maxDailyCalls()));}
    public synchronized Map<String,Object> save(Draft d){
        if(d==null||d.id()==null||!d.id().matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")||!Set.of("TENCENT","BRAVE","TAVILY").contains(d.kind()))throw new IllegalArgumentException("Invalid Provider definition");
        var uri=PublicWebHttp.validate(d.endpoint());if(!uri.getScheme().equals("https")||uri.getRawQuery()!=null)throw new IllegalArgumentException("Provider requires HTTPS without query credentials");
        var old=find(d.id());String cipher=old==null?null:old.credentialCipher();
        if(d.apiKey()!=null&&!d.apiKey().isBlank())cipher=encrypt(Map.of("apiKey",d.apiKey().trim(),"secretId",Objects.toString(d.secretId(),"")));
        if(old!=null&&!old.kind().equals(d.kind())&&(d.apiKey()==null||d.apiKey().isBlank()))throw new IllegalArgumentException("Changing Provider type requires credentials");
        if(d.kind().equals("TENCENT")&&d.apiKey()!=null&&!d.apiKey().isBlank()&&(d.secretId()==null||d.secretId().isBlank()))throw new IllegalArgumentException("Tencent requires server-side secret ID and key");
        String name=d.name()==null?d.id():d.name().trim();if(name.isEmpty()||name.length()>128)throw new IllegalArgumentException("Invalid Provider name");
        var p=new Provider(d.id(),d.kind(),name,uri.toString(),cipher,Boolean.TRUE.equals(d.enabled()),bounded(d.timeoutMillis(),10000,1000,30000),bounded(d.requestsPerMinute(),30,1,600),bounded(d.maxTaskCalls(),12,1,50),bounded(d.maxDailyCalls(),1000,1,100000),old==null?1:old.revision()+1,"NOT_TESTED");
        if(p.enabled()&&p.credentialCipher()==null)throw new IllegalArgumentException("Configure server-side Provider credentials first");
        transactions.executeWithoutResult(status->{jdbc.update("DELETE FROM internet_search_provider WHERE id=?",p.id());insert(p);});return view(p);
    }
    private static int bounded(Integer n,int fallback,int min,int max){int v=n==null?fallback:n;if(v<min||v>max)throw new IllegalArgumentException("Invalid Provider limit");return v;}
    private String cryptoKey(){if(secrets.getCryptoKey()!=null&&!secrets.getCryptoKey().isBlank())return secrets.getCryptoKey();try{return secrets.getCryptoKeyFile()==null?"":java.nio.file.Files.readString(java.nio.file.Path.of(secrets.getCryptoKeyFile())).trim();}catch(Exception e){return "";}}
    private String encrypt(Map<String,String> values){try{String key=cryptoKey();if(key.isBlank())throw new IllegalStateException("Server encryption key required");return InternalSecretCipher.encrypt(json.writeValueAsString(values),key);}catch(java.io.IOException e){throw new IllegalStateException("Cannot encode Provider credential");}}
    public Map<String,String> credentials(Provider p){try{if(!InternalSecretCipher.isEncrypted(p.credentialCipher()))throw new IllegalStateException("Encrypted Provider credentials required");return json.readValue(InternalSecretCipher.decryptIfNecessary(p.credentialCipher(),cryptoKey()),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});}catch(Exception e){throw new PublicWebHttp.Rejected("PROVIDER_CREDENTIAL_UNAVAILABLE");}}
    public synchronized void reserve(Provider p,String taskScope){
        transactions.executeWithoutResult(tx->{
            long now=System.currentTimeMillis();increment(p.id()+":day:"+(now/86400000),p.maxDailyCalls(),"PROVIDER_DAILY_QUOTA");
            increment(p.id()+":minute:"+(now/60000),p.requestsPerMinute(),"PROVIDER_RATE_LIMIT");
            increment(p.id()+":task:"+taskScope,p.maxTaskCalls(),"TASK_SEARCH_QUOTA");
        });
    }
    private void increment(String bucket,int max,String error){
        if(jdbc.update("UPDATE internet_search_quota SET calls_used=calls_used+1 WHERE bucket_id=? AND calls_used<?",bucket,max)>0)return;
        Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM internet_search_quota WHERE bucket_id=?",Integer.class,bucket);
        if(exists!=null&&exists>0)throw new PublicWebHttp.Rejected(error);
        try{jdbc.update("INSERT INTO internet_search_quota VALUES (?,1)",bucket);}catch(DuplicateKeyException e){throw new PublicWebHttp.Rejected(error);}
    }
    public boolean begin(String request,String task,String tenant,String user,String agent,String provider,String tool,String hash){
        try{jdbc.update("INSERT INTO internet_search_request(request_id,task_id,tenant_id,user_id,agent_id,provider_id,tool_name,request_hash,started_at,status) VALUES (?,?,?,?,?,?,?,?,?,?)",request,task,tenant,user,agent,provider,tool,hash,System.currentTimeMillis(),"IN_FLIGHT");return true;}
        catch(DuplicateKeyException e){var row=jdbc.queryForMap("SELECT * FROM internet_search_request WHERE request_id=?",request);if(!Objects.equals(hash,row.get("request_hash"))||!Objects.equals(task,row.get("task_id"))||!Objects.equals(tenant,row.get("tenant_id"))||!Objects.equals(user,row.get("user_id")))throw new SecurityException("Internet call identity conflict");return false;}
    }
    public Map<String,Object> replay(String request){try{String result=jdbc.queryForObject("SELECT result_json FROM internet_search_request WHERE request_id=?",String.class,request);return result==null?Map.of("status","ERROR","errorCode","REMOTE_OUTCOME_UNKNOWN"):json.readValue(result,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}catch(java.io.IOException e){throw new IllegalStateException("Invalid stored Internet receipt");}}
    public void finish(String request,Provider p,Map<String,Object> result,long elapsed){try{
        String encoded=json.writeValueAsString(result),error=Objects.toString(result.get("errorCode"),"");
        jdbc.update("UPDATE internet_search_request SET elapsed_ms=?,result_count=?,response_bytes=?,status=?,error_code=?,result_json=? WHERE request_id=?",elapsed,((Number)result.getOrDefault("count",0)).intValue(),encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,error.isEmpty()?"SUCCEEDED":"FAILED",error,encoded,request);
        jdbc.update("UPDATE internet_search_provider SET last_status=? WHERE id=?",error.isEmpty()?"HEALTHY":error,p.id());
    }catch(java.io.IOException e){throw new IllegalStateException("Cannot archive Internet receipt");}}
}
