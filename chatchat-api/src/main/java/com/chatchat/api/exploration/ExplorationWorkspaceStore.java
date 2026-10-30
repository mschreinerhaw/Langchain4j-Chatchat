package com.chatchat.api.exploration;

import com.chatchat.api.assets.UserAssetService.Owner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Business exploration state only. Execution and evidence remain owned by Runtime. */
@Service
public class ExplorationWorkspaceStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ExplorationWorkspaceStore(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public ObjectNode create(Owner owner,String title){
        return create(owner,title,List.of());
    }
    public ObjectNode create(Owner owner,String title,List<String> fileIds){
        if(title==null||title.isBlank()||title.length()>120)throw new IllegalArgumentException("工作空间名称应为 1–120 字");
        var state=json.createObjectNode();state.put("id",UUID.randomUUID().toString());state.put("title",title.strip());
        state.put("version",0);state.putArray("nodes");
        state.set("fileIds",json.valueToTree(fileIds));
        jdbc.update("insert into runtime_exploration_workspace(id,tenant_id,user_id,state_json,version,updated_at) values(?,?,?,?,0,?)",
            state.path("id").asText(),owner.tenant(),owner.user(),encode(state),System.currentTimeMillis());return state;
    }
    public List<ObjectNode> list(Owner owner){
        return jdbc.query("select state_json from runtime_exploration_workspace where tenant_id=? and user_id=? order by updated_at desc limit 100",
            (rs,n)->{var state=decode(rs.getString(1));state.remove("nodes");return state;},owner.tenant(),owner.user());
    }
    public ObjectNode get(Owner owner,String id){
        var rows=jdbc.query("select state_json from runtime_exploration_workspace where id=? and tenant_id=? and user_id=?",
            (rs,n)->decode(rs.getString(1)),id,owner.tenant(),owner.user());
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"工作空间不存在");return rows.get(0);
    }
    public ObjectNode save(Owner owner,ObjectNode state,long expected){
        state.put("version",expected+1);String encoded=encode(state);
        if(encoded.length()>4_000_000)throw new IllegalArgumentException("工作空间达到容量上限，请创建新空间");
        if(jdbc.update("update runtime_exploration_workspace set state_json=?,version=?,updated_at=? where id=? and tenant_id=? and user_id=? and version=?",
            encoded,expected+1,System.currentTimeMillis(),state.path("id").asText(),owner.tenant(),owner.user(),expected)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"工作空间已更新，请刷新后重试");
        return state;
    }
    private String encode(ObjectNode state){try{return json.writeValueAsString(state);}catch(Exception e){throw new IllegalStateException(e);}}
    private ObjectNode decode(String value){try{return (ObjectNode)json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
