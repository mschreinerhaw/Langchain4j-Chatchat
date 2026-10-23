package com.chatchat.mcpserver.templatepublication.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Current business classification belongs to a tool, never to an immutable released snapshot. */
@Service @RequiredArgsConstructor
public class ToolCategoryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final String VISIBLE = " and (r.actor_id<>'legacy-migration' or not exists(select 1 from mcp_tool_release managed where managed.tenant_id=r.tenant_id and managed.actor_id<>'legacy-migration' and managed.binding_id=r.binding_id and managed.logical_id<>r.logical_id))";
    public record Category(String id, String name, long version, long toolCount) { }
    public record Edit(String name, Long expectedVersion) { }
    public record Removal(Long expectedVersion, Long expectedToolCount, String targetCategoryId) { }
    public record Assignment(String categoryId, String expectedCategoryId) { }

    @Transactional
    public void initialize() {
        jdbc.execute("create table if not exists mcp_tool_category_scope (tenant_id varchar(64) primary key)");
        jdbc.execute("create table if not exists mcp_tool_category (id varchar(64) primary key, tenant_id varchar(64) not null, name varchar(64) not null, name_key varchar(128) not null, version bigint not null default 0, unique(tenant_id,name_key))");
        jdbc.execute("create table if not exists mcp_tool_category_assignment (tenant_id varchar(64) not null, logical_id varchar(64) not null, category_id varchar(64), primary key(tenant_id,logical_id))");
        // Import once per logical tool. An explicit unclassified assignment prevents resurrection after deletion.
        var rows = jdbc.queryForList("select r.tenant_id,r.logical_id,r.definition_json from mcp_tool_release r where r.state <> 'DELETED' and r.kind='CONTROLLED_TEMPLATE'"+VISIBLE+" order by r.release_number desc");
        for (var row : rows) {
            String tenant = (String)row.get("tenant_id"), logical = (String)row.get("logical_id");
            lock(tenant);
            if (!jdbc.queryForList("select logical_id from mcp_tool_category_assignment where tenant_id=? and logical_id=?",tenant,logical).isEmpty()) continue;
            String name;
            try { name = json.readTree(String.valueOf(row.get("definition_json"))).path("businessCategory").asText("").strip(); }
            catch (Exception e) { throw new IllegalStateException("Cannot import tool classification",e); }
            String id = null;
            if (!name.isBlank() && !Set.of("未分类","待分类","全部分类").contains(name)) {
                var existing = list(tenant).stream().filter(c -> key(c.name()).equals(key(name))).findFirst();
                id = existing.isPresent() ? existing.get().id() : create(tenant,new Edit(name,null)).id();
            }
            jdbc.update("insert into mcp_tool_category_assignment(tenant_id,logical_id,category_id) values(?,?,?)",tenant,logical,id);
        }
    }

    @Transactional(readOnly=true)
    public List<Category> list(String tenant) {
        return jdbc.query("select c.id,c.name,c.version,(select count(*) from mcp_tool_category_assignment a where a.tenant_id=c.tenant_id and a.category_id=c.id and exists(select 1 from mcp_tool_release r where r.tenant_id=a.tenant_id and r.logical_id=a.logical_id and r.kind='CONTROLLED_TEMPLATE' and r.state<>'DELETED'"+VISIBLE+")) as tool_count from mcp_tool_category c where c.tenant_id=? order by c.name,c.id",
            (r,n) -> new Category(r.getString("id"),r.getString("name"),r.getLong("version"),r.getLong("tool_count")),tenant);
    }

    @Transactional
    public Category create(String tenant, Edit edit) {
        lock(tenant); String name = name(edit == null ? null : edit.name()); requireUnique(tenant,name,null);
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into mcp_tool_category(id,tenant_id,name,name_key,version) values(?,?,?,?,0)",id,tenant,name,key(name));
        return new Category(id,name,0,0);
    }

    @Transactional
    public Category rename(String tenant, String id, Edit edit) {
        lock(tenant); Category current = find(tenant,id); expected(current,edit == null ? null : edit.expectedVersion());
        String name = name(edit.name()); requireUnique(tenant,name,id);
        jdbc.update("update mcp_tool_category set name=?,name_key=?,version=version+1 where tenant_id=? and id=?",name,key(name),tenant,id);
        return new Category(id,name,current.version()+1,current.toolCount());
    }

    @Transactional
    public void delete(String tenant, String id, Removal request) {
        lock(tenant); Category current = find(tenant,id); expected(current,request == null ? null : request.expectedVersion());
        if (!Objects.equals(request.expectedToolCount(),current.toolCount())) conflict("关联工具已变化，请刷新后重新确认");
        String target = normalize(request.targetCategoryId());
        if (id.equals(target)) throw new IllegalArgumentException("请选择其他分类");
        if (target != null) find(tenant,target);
        jdbc.update("update mcp_tool_category_assignment set category_id=? where tenant_id=? and category_id=?",target,tenant,id);
        jdbc.update("delete from mcp_tool_category where tenant_id=? and id=?",tenant,id);
    }

    @Transactional
    public void assign(String tenant, String logical, Assignment request) {
        lock(tenant);
        if (jdbc.queryForObject("select count(*) from mcp_tool_release r where r.tenant_id=? and r.logical_id=? and r.kind='CONTROLLED_TEMPLATE' and r.state<>'DELETED'"+VISIBLE,Long.class,tenant,logical)==0) missing();
        var rows=jdbc.queryForList("select category_id from mcp_tool_category_assignment where tenant_id=? and logical_id=?",tenant,logical);
        String current=rows.isEmpty()?null:(String)rows.get(0).get("category_id");
        if (request==null || !Objects.equals(current,normalize(request.expectedCategoryId()))) conflict("工具分类已被调整，请刷新后重试");
        saveAssignment(tenant,logical,normalize(request.categoryId()));
    }

    @Transactional
    public void assignByName(String tenant, String logical, String name) {
        lock(tenant); String id=null;
        if (name!=null && !name.isBlank()) id=list(tenant).stream().filter(c->c.name().equals(name.strip())).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"分类已变更，请刷新并重新选择")).id();
        saveAssignment(tenant,logical,id);
    }

    public void project(String tenant, List<ToolRelease> releases) {
        var assignments=new HashMap<String,String>();
        jdbc.query("select logical_id,category_id from mcp_tool_category_assignment where tenant_id=?",r->{assignments.put(r.getString(1),r.getString(2));},tenant);
        var names=new HashMap<String,String>(); for(var c:list(tenant)) names.put(c.id(),c.name());
        for(var row:releases) {String id=assignments.get(row.getLogicalId()); row.setBusinessCategoryId(id); row.setBusinessCategory(id==null?"":names.getOrDefault(id,""));}
    }
    private void saveAssignment(String tenant,String logical,String id) {
        if(id!=null)find(tenant,id);
        if(jdbc.update("update mcp_tool_category_assignment set category_id=? where tenant_id=? and logical_id=?",id,tenant,logical)==0)
            jdbc.update("insert into mcp_tool_category_assignment(tenant_id,logical_id,category_id) values(?,?,?)",tenant,logical,id);
    }
    private void lock(String tenant) {
        boolean postgres=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")));
        if(postgres) jdbc.update("insert into mcp_tool_category_scope(tenant_id) values(?) on conflict do nothing",tenant);
        else jdbc.update("merge into mcp_tool_category_scope(tenant_id) key(tenant_id) values(?)",tenant);
        jdbc.queryForList("select tenant_id from mcp_tool_category_scope where tenant_id=? for update",tenant);
    }
    private Category find(String tenant,String id) {return list(tenant).stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"分类不存在，请刷新后重试"));}
    private void expected(Category c,Long version) {if(version==null || c.version()!=version) conflict("分类已被修改，请刷新后重试");}
    private void requireUnique(String tenant,String name,String id) {if(list(tenant).stream().anyMatch(c->!c.id().equals(id)&&key(c.name()).equals(key(name)))) conflict("该分类名称已存在，请使用其他名称");}
    private static String key(String name) {return name.toLowerCase(Locale.ROOT);}
    private static String name(String value) {
        String name=value==null?"":value.strip();
        if(name.isBlank() || name.length()>64 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("请输入 1–64 个字符的分类名称");
        if(Set.of("未分类","待分类","全部分类").contains(name)) throw new IllegalArgumentException("该名称为系统保留名称，请使用其他名称");
        return name;
    }
    private static String normalize(String id) {return id==null||id.isBlank()?null:id;}
    private static void conflict(String message) {throw new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private static void missing() {throw new ResponseStatusException(HttpStatus.NOT_FOUND,"工具不存在");}
}
