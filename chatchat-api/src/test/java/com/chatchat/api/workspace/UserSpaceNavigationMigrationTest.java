package com.chatchat.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class UserSpaceNavigationMigrationTest {
    @Test void mergesOnlyBuiltInPagesAndKeepsCustomFieldsRolesAndOtherMenus()throws Exception{
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:user-space-nav;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("create table workspace_registry(id varchar primary key,manifest_json varchar,version bigint,updated_at timestamp)");
        jdbc.execute("create table sys_menu(menu_code varchar primary key,menu_name varchar,icon varchar,status varchar,updated_at timestamp)");
        var json=new ObjectMapper();String custom="""
            {"configVersion":4,"workspaceId":"data-science","name":"Custom studio","entryPage":"dataScienceScripts",
             "customSetting":"keep-me","pages":[{"id":"dataScienceData","componentKey":"dataScienceData"},
             {"id":"dataScienceScripts","componentKey":"dataScienceScripts"},{"id":"custom","componentKey":"sqlStudio","props":{"keep":true}}]}
            """;
        jdbc.update("insert into workspace_registry values('data-science',?,9,CURRENT_TIMESTAMP)",custom);
        jdbc.update("insert into sys_menu values('dataScienceData',?,'file','enabled',CURRENT_TIMESTAMP)","我的数据");
        jdbc.update("insert into sys_menu values('dataScienceScripts',?,'book','enabled',CURRENT_TIMESTAMP)","我的脚本");
        var migration=new UserSpaceNavigationMigration(jdbc,json);migration.migrate();migration.migrate();
        var changed=json.readTree(jdbc.queryForObject("select manifest_json from workspace_registry",String.class));
        assertThat(changed.path("pages")).hasSize(2);assertThat(changed.path("customSetting").asText()).isEqualTo("keep-me");
        assertThat(changed.path("entryPage").asText()).isEqualTo("dataScienceData");
        assertThat(jdbc.queryForObject("select version from workspace_registry",Long.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select menu_name from sys_menu where menu_code='dataScienceData'",String.class)).isEqualTo("用户空间");
        assertThat(jdbc.queryForObject("select status from sys_menu where menu_code='dataScienceScripts'",String.class)).isEqualTo("disabled");
        assertThat(changed.path("pages").get(1).path("props").path("keep").asBoolean()).isTrue();
    }
}
