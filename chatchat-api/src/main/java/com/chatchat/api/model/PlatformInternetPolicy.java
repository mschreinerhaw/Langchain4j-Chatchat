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
    @PostConstruct public void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS platform_model_internet_policy (model_name VARCHAR(128) PRIMARY KEY,enabled BOOLEAN NOT NULL,provider_id VARCHAR(64),revision BIGINT NOT NULL)");}
    public record Policy(boolean enabled,String providerId,long revision) { }
    public Policy read(String model){
        if(model==null||model.isBlank())return new Policy(false,null,0);
        return jdbc.query("SELECT enabled,provider_id,revision FROM platform_model_internet_policy WHERE model_name=?",
            rs->rs.next()?new Policy(rs.getBoolean(1),rs.getString(2),rs.getLong(3)):new Policy(false,null,0),model);
    }
    public JsonNode providers(){return mcp.request("GET","/api/v1/internet-search/providers",null);}
    public boolean active(String model){return jdbc.queryForObject("SELECT COUNT(*) FROM platform_model_publication WHERE model_type='chat' AND name=? AND enabled=TRUE",Integer.class,model)>0;}
    @Transactional public void save(String model,Boolean enabled,String provider){
        if(enabled==null&&provider==null)return; // Older model-management clients retain the live capability.
        var old=read(model);boolean on=Boolean.TRUE.equals(enabled);
        String id=provider==null||provider.isBlank()?null:provider.trim();
        if(id!=null&&!id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"))throw new IllegalArgumentException("Invalid search Provider ID");
        if(on&&(id==null||!java.util.stream.StreamSupport.stream(providers().spliterator(),false)
            .anyMatch(p->id.equals(p.path("id").asText())&&p.path("enabled").asBoolean()&&p.path("configured").asBoolean())))
            throw new IllegalArgumentException("Select an enabled, configured search Provider");
        if(old.enabled()==on&&Objects.equals(old.providerId(),id))return;
        int changed=jdbc.update("UPDATE platform_model_internet_policy SET enabled=?,provider_id=?,revision=revision+1 WHERE model_name=?",on,id,model);
        if(changed==0)jdbc.update("INSERT INTO platform_model_internet_policy(model_name,enabled,provider_id,revision) VALUES (?,?,?,1)",model,on,id);
    }
}
