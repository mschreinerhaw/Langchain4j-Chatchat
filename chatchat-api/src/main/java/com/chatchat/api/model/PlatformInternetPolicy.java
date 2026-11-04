package com.chatchat.api.model;

import com.chatchat.api.controller.mcp.McpControlPlaneClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Live model capability, separate from model credentials and publication drafts. */
@Service
public class PlatformInternetPolicy {
    private final JdbcTemplate jdbc;
    private final McpControlPlaneClient mcp;
    public PlatformInternetPolicy(JdbcTemplate jdbc,McpControlPlaneClient mcp){this.jdbc=jdbc;this.mcp=mcp;}
    @PostConstruct public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS platform_model_internet_policy (model_name VARCHAR(128) PRIMARY KEY,enabled BOOLEAN NOT NULL,provider_id VARCHAR(64),revision BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS platform_model_internet_provider (model_name VARCHAR(128) NOT NULL,provider_id VARCHAR(64) NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(model_name,provider_id))");
    }
    public record Policy(boolean enabled,String providerId,long revision,List<String> providerIds) {
        public Policy(boolean enabled,String providerId,long revision){this(enabled,providerId,revision,providerId==null?List.of():List.of(providerId));}
    }
    public Policy read(String model){
        if(model==null||model.isBlank())return new Policy(false,null,0);
        var policy=jdbc.query("SELECT enabled,provider_id,revision FROM platform_model_internet_policy WHERE model_name=?",
            rs->rs.next()?new Policy(rs.getBoolean(1),rs.getString(2),rs.getLong(3)):new Policy(false,null,0),model);
        var ids=jdbc.query("SELECT provider_id FROM platform_model_internet_provider WHERE model_name=? ORDER BY position",(rs,row)->rs.getString(1),model);
        return ids.isEmpty()?policy:new Policy(policy.enabled(),ids.get(0),policy.revision(),List.copyOf(ids));
    }
    public JsonNode providers(){return mcp.request("GET","/api/v1/internet-search/providers",null);}
    public JsonNode saveProvider(JsonNode draft){return mcp.request("POST","/api/v1/internet-search/providers",draft);}
    public void deleteProvider(String id){
        if(id==null||!id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"))throw new IllegalArgumentException("Invalid search Provider ID");
        mcp.request("DELETE","/api/v1/internet-search/providers/"+id,null);
    }
    public boolean active(String model){return jdbc.queryForObject("SELECT COUNT(*) FROM platform_model_publication WHERE model_type='chat' AND name=? AND enabled=TRUE",Integer.class,model)>0;}
    @Transactional public void save(String model,Boolean enabled,String provider){
        save(model,enabled,provider,null);
    }
    @Transactional public void save(String model,Boolean enabled,String provider,List<String> providers){
        if(enabled==null&&provider==null&&providers==null)return; // Older clients retain the live capability.
        var old=read(model);boolean on=Boolean.TRUE.equals(enabled);
        if(providers!=null&&providers.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Invalid search Provider selection");
        var ids=providers==null?(provider==null||provider.isBlank()?List.<String>of():List.of(provider.trim())):List.copyOf(providers);
        if(ids.size()>16||new HashSet<>(ids).size()!=ids.size()||ids.stream().anyMatch(id->!id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")))throw new IllegalArgumentException("Invalid search Provider selection");
        if(on){
            Set<String> ready=new HashSet<>();providers().forEach(p->{if(p.path("enabled").asBoolean()&&p.path("configured").asBoolean())ready.add(p.path("id").asText());});
            if(ids.isEmpty()||!ready.containsAll(ids))throw new IllegalArgumentException("请选择已配置并启用的检索引擎");
        }
        if(old.enabled()==on&&Objects.equals(old.providerIds(),ids))return;
        String id=ids.isEmpty()?null:ids.get(0);
        int changed=jdbc.update("UPDATE platform_model_internet_policy SET enabled=?,provider_id=?,revision=revision+1 WHERE model_name=?",on,id,model);
        if(changed==0)jdbc.update("INSERT INTO platform_model_internet_policy(model_name,enabled,provider_id,revision) VALUES (?,?,?,1)",model,on,id);
        jdbc.update("DELETE FROM platform_model_internet_provider WHERE model_name=?",model);
        for(int i=0;i<ids.size();i++)jdbc.update("INSERT INTO platform_model_internet_provider(model_name,provider_id,position) VALUES (?,?,?)",model,ids.get(i),i);
    }
}
