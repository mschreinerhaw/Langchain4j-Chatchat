package com.chatchat.api.enterprise.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class SkillRoleQueryServiceTest {
    private JdbcTemplate jdbc;
    private SkillRoleQueryService service;
    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); service = new SkillRoleQueryService(new NamedParameterJdbcTemplate(ds));
        jdbc.execute("create table sys_role(id varchar primary key,tenant_id varchar,role_name varchar,role_code varchar,description varchar,status varchar)");
        jdbc.execute("create table skill_config(id varchar primary key,label varchar,description varchar,skill_tags_json varchar,usage_scenarios_json varchar,market_status varchar)");
        jdbc.execute("create table ds_domain_skill(id varchar primary key,tenant_id varchar,name varchar,description varchar,status varchar,search_text varchar,builtin boolean)");
        jdbc.execute("create table role_agent_binding(tenant_id varchar,role_id varchar,agent_id varchar,enabled boolean,effective_time timestamp,expire_time timestamp)");
        jdbc.execute("create table resource_grant(tenant_id varchar,principal_type varchar,principal_id varchar,resource_type varchar,resource_id varchar,agent_id varchar,effect varchar,enabled boolean,expires_at timestamp)");
        for (int i = 0; i < 100; i++) jdbc.update("insert into sys_role values(?, 't', ?, ?, '', 'enabled')", "r"+i, "角色"+i, "ROLE"+i);
        for (int i = 0; i < 1000; i++) jdbc.update("insert into skill_config values(?, ?, '', '固收', '', 'published')", "a"+i, "Agent"+i);
        jdbc.update("insert into ds_domain_skill values('s', 't', '固收晨报', '', 'PUBLISHED', '固收晨报', false)");
        jdbc.update("insert into role_agent_binding values('t','r0','a0',true,null,null)");
        jdbc.update("insert into resource_grant values('t','ROLE','r0','SKILL','s','a0','ALLOW',true,null)");
    }
    @Test
    void pagesLargeCatalogAndEscapesSearchWithoutReturningFullMatrix() {
        var roles = service.query("t", "roles", "", "", "", "", 1, 20);
        assertThat(roles.total()).isEqualTo(100); assertThat(roles.items()).hasSize(20);
        var skills = service.query("t", "skills", "固收", "", "", "", 1, 20);
        assertThat(skills.total()).isEqualTo(1001); assertThat(skills.items()).hasSize(20);
        assertThat(service.query("t", "skills", "%", "", "", "", 1, 20).total()).isZero();
        assertThat(service.query("t", "skills", "", "", "", "", 500, 1000).items()).hasSize(1);
        assertThat(service.query("other", "roles", "", "", "", "", 1, 20).total()).isZero();
    }
    @Test
    void queriesBothDirectionsWithAgentScopeAndRevocation() {
        var role = service.query("t", "relations", "", "", "r0", "", 1, 20);
        assertThat(role.total()).isEqualTo(2);
        var inverse = service.query("t", "relations", "", "SKILL", "", "s", 1, 20);
        assertThat(inverse.items()).singleElement().satisfies(row -> {
            assertThat(row.get("role_id")).isEqualTo("r0"); assertThat(row.get("agent_id")).isEqualTo("a0");
        });
        jdbc.update("insert into resource_grant values('t','ROLE','r0','SKILL','s','a0','DENY',true,null)");
        assertThat(service.query("t", "relations", "", "SKILL", "", "s", 1, 20).total()).isZero();
        assertThat(service.query("other", "relations", "", "", "r0", "", 1, 20).total()).isZero();
    }
    @Test
    void paginatesOneHundredRolesWithOneThousandAgentsEachInBothDirections() {
        for (int i = 0; i < 100; i++) jdbc.update(
            "insert into resource_grant values('t','ROLE',?,'AGENT_SKILL','*',null,'ALLOW',true,null)", "r"+i);
        var forward = service.query("t", "relations", "", "AGENT_SKILL", "r1", "", 2, 20);
        assertThat(forward.total()).isEqualTo(1000); assertThat(forward.items()).hasSize(20);
        assertThat(forward.page()).isEqualTo(2);
        var reverse = service.query("t", "relations", "", "AGENT_SKILL", "", "a1", 1, 20);
        assertThat(reverse.total()).isEqualTo(100); assertThat(reverse.items()).hasSize(20);
    }

    @Test
    void honorsExpiryDisabledBindingsAndSharedWildcardDeny() {
        jdbc.update("update role_agent_binding set enabled=false");
        assertThat(service.query("t", "relations", "", "", "r0", "", 1, 20).total()).isZero();
        jdbc.update("insert into resource_grant values('t','ROLE','r0','SKILL','*',null,'ALLOW',true,null)");
        assertThat(service.query("t", "relations", "", "", "r0", "", 1, 20).total()).isEqualTo(1);
        jdbc.update("insert into resource_grant values('t','ROLE','r0','SKILL','*',null,'DENY',true,null)");
        assertThat(service.query("t", "relations", "", "", "r0", "", 1, 20).total()).isZero();
        assertThatThrownBy(() -> service.query("t", "relations", "", "", "", "", 1, 20)).isInstanceOf(IllegalArgumentException.class);
    }
}
