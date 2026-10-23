package com.chatchat.mcpserver.templatepublication.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ToolCategoryServiceTest {
    JdbcTemplate jdbc; ToolCategoryService service; TransactionTemplate tx;
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:category_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(source); tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("create table mcp_tool_release(id varchar(64),tenant_id varchar(64),logical_id varchar(64),release_number int,state varchar(32),kind varchar(64),definition_json varchar(4096),actor_id varchar(64) default 'user',binding_id varchar(64))");
        service=new ToolCategoryService(jdbc,new ObjectMapper());
        tx.executeWithoutResult(s->service.initialize());
    }
    void release(String id,String tenant,String logical,int number,String category) {
        jdbc.update("insert into mcp_tool_release(id,tenant_id,logical_id,release_number,state,kind,definition_json) values(?,?,?,?,?,?,?)",id,tenant,logical,number,"RUNNING","CONTROLLED_TEMPLATE","{\"businessCategory\":\""+category+"\"}");
    }
    @Test void renameAndTransferAffectAllVersionsWithoutChangingPublishedDefinitions() {
        release("v1","a","tool",1,"客户分析");release("v2","a","tool",2,"客户分析");
        tx.executeWithoutResult(s->service.initialize());
        var first=service.list("a").get(0);
        assertThat(first.toolCount()).isEqualTo(1);
        var original=jdbc.queryForList("select * from mcp_tool_release");
        var renamed=tx.execute(s->service.rename("a",first.id(),new ToolCategoryService.Edit("客户洞察",first.version())));
        var row=new ToolRelease(); row.setLogicalId("tool"); service.project("a",List.of(row));
        assertThat(row.getBusinessCategory()).isEqualTo("客户洞察");
        var target=tx.execute(s->service.create("a",new ToolCategoryService.Edit("经营分析",null)));
        tx.executeWithoutResult(s->service.delete("a",first.id(),new ToolCategoryService.Removal(renamed.version(),1L,target.id())));
        service.project("a",List.of(row));assertThat(row.getBusinessCategory()).isEqualTo("经营分析");
        tx.executeWithoutResult(s->service.delete("a",target.id(),new ToolCategoryService.Removal(target.version(),1L,null)));
        tx.executeWithoutResult(s->service.initialize());
        service.project("a",List.of(row)); assertThat(row.getBusinessCategory()).isEmpty();
        assertThat(service.list("a")).isEmpty();
        assertThat(jdbc.queryForList("select * from mcp_tool_release")).isEqualTo(original);
    }
    @Test void staleDeleteDuplicateNamesAndCrossTenantTargetsDoNotChangeAssignments() {
        release("v1","a","tool",1,""); tx.executeWithoutResult(s->service.initialize());
        var a=tx.execute(s->service.create("a",new ToolCategoryService.Edit("Sales",null)));
        var b=tx.execute(s->service.create("b",new ToolCategoryService.Edit("Sales",null)));
        assertThatThrownBy(()->tx.execute(s->service.create("a",new ToolCategoryService.Edit(" sales ",null)))).hasMessageContaining("已存在");
        assertThatThrownBy(()->tx.execute(s->service.rename("b",a.id(),new ToolCategoryService.Edit("Other",0L)))).hasMessageContaining("不存在");
        tx.executeWithoutResult(s->service.assign("a","tool",new ToolCategoryService.Assignment(a.id(),null)));
        assertThatThrownBy(()->tx.execute(s->{service.delete("a",a.id(),new ToolCategoryService.Removal(0L,0L,null));return null;})).hasMessageContaining("关联工具已变化");
        assertThatThrownBy(()->tx.execute(s->{service.delete("a",a.id(),new ToolCategoryService.Removal(0L,1L,b.id()));return null;})).hasMessageContaining("不存在");
        assertThatThrownBy(()->tx.execute(s->service.rename("a",a.id(),new ToolCategoryService.Edit("Other",9L)))).hasMessageContaining("已被修改");
        assertThatThrownBy(()->tx.execute(s->{service.assign("a","tool",new ToolCategoryService.Assignment(null,null));return null;})).hasMessageContaining("已被调整");
        assertThat(service.list("a").get(0).toolCount()).isOne();
    }
    @Test void assignmentRequiresAnExistingCategoryAndToolAndCountsExcludeDeletedTools() {
        release("v1","a","tool",1,"");tx.executeWithoutResult(s->service.initialize());
        assertThatThrownBy(()->tx.execute(s->{service.assignByName("a","tool","未知分类");return null;})).hasMessageContaining("重新选择");
        var c=tx.execute(s->service.create("a",new ToolCategoryService.Edit("分析",null)));
        assertThatThrownBy(()->tx.execute(s->{service.assign("a","absent",new ToolCategoryService.Assignment(c.id(),null));return null;})).hasMessageContaining("不存在");
        tx.executeWithoutResult(s->service.assign("a","tool",new ToolCategoryService.Assignment(c.id(),null)));
        jdbc.update("update mcp_tool_release set state='DELETED'");
        assertThat(service.list("a").get(0).toolCount()).isZero();
    }
}
