package com.chatchat.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class RetiredAnalysisMigrationTest {
    private JdbcTemplate fixture() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:retirement_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table sys_menu(id varchar primary key, parent_id varchar,menu_code varchar,menu_name varchar,status varchar)");
        jdbc.execute("create table sys_permission(id varchar primary key,permission_code varchar,permission_name varchar,resource_path varchar)");
        jdbc.execute("create table sys_role_permission(id varchar primary key,permission_id varchar,role_id varchar)");
        jdbc.execute("create table sys_menu_permission(id varchar primary key,permission_id varchar,menu_id varchar)");
        jdbc.execute("create table workspace_registry(id varchar primary key,manifest_json text,version bigint,updated_at timestamp)");
        jdbc.update("insert into sys_menu values('old',null,'domainAnalysis','retired','enabled'),('chat',null,'chat','chat','enabled')");
        jdbc.update("insert into sys_permission values('old','workspace:analysis:api','old','/api/v1/agent/analysis/**'),('runtime','system:agent-registry:manage','old registry','/api/v1/enterprise/agent-registry/**'),('data','business:data:read','data','/business')");
        jdbc.update("insert into sys_role_permission values('retired','old','r'),('shared','runtime','r'),('data','data','r')");
        jdbc.update("insert into sys_menu_permission values('retired','old','old'),('chat','data','chat')");
        jdbc.update("insert into workspace_registry values('custom',?,7,CURRENT_TIMESTAMP)", """
            {"entryPage":"domainAnalysis","configVersion":3,"customSetting":"preserve",
             "pages":[{"id":"chat","componentKey":"chat"},{"id":"domainAnalysis","componentKey":"domainAnalysis"},{"id":"report","componentKey":"report"}]}
            """);
        return jdbc;
    }
    @Test void removesOnlyRetiredMetadataAndArchivesExactConfigurationOnce() throws Exception {
        var jdbc = fixture();
        String before = jdbc.queryForObject("select manifest_json from workspace_registry", String.class);
        var migration = new RetiredAnalysisMigration(jdbc, new ObjectMapper());
        migration.migrate(); migration.migrate();
        assertThat(jdbc.queryForObject("select count(*) from sys_menu where menu_code='domainAnalysis'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sys_permission where permission_code='workspace:analysis:api'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sys_role_permission", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from sys_menu_permission", Integer.class)).isOne();
        var json = new ObjectMapper();
        String saved = jdbc.queryForObject("select saved_payload from runtime_product_retirement_archive where record_type='workspace_registry'", String.class);
        assertThat(json.readTree(saved).path("MANIFEST_JSON").asText()).isEqualTo(before);
        var after = json.readTree(jdbc.queryForObject("select manifest_json from workspace_registry", String.class));
        assertThat(after.path("customSetting").asText()).isEqualTo("preserve");
        assertThat(after.path("entryPage").asText()).isEqualTo("chat");
        assertThat(after.path("pages").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select version from workspace_registry", Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from runtime_product_retirement_archive where record_type='MARKER'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select permission_name from sys_permission where id='runtime'", String.class)).isEqualTo("Runtime 运行配置管理");
    }
    @Test void refusesToRemoveAnOldMenuThatOwnsCustomizedChildren() {
        var jdbc = fixture();
        jdbc.update("insert into sys_menu values('custom','old','customBusiness','custom','enabled')");
        assertThatThrownBy(() -> new RetiredAnalysisMigration(jdbc, new ObjectMapper()).migrate())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("custom children");
        assertThat(jdbc.queryForObject("select count(*) from sys_menu", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from sys_role_permission", Integer.class)).isEqualTo(3);
    }
}
