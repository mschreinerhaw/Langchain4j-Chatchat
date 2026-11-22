package com.chatchat.api.workspace;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class BusinessWorkspaceMigration {
    static final String ID = "business";
    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    @Order(175)
    @Transactional
    public void seed() throws java.io.IOException {
        jdbc.execute("""
            create table if not exists business_workspace_user_note (
                id varchar(64) primary key,
                tenant_id varchar(64) not null,
                owner_id varchar(64) not null,
                panel varchar(16) not null,
                title varchar(120) not null,
                body text not null,
                published_title varchar(120),
                published_body text,
                role_id varchar(64),
                revision bigint not null,
                updated_at timestamp not null
            )
            """);
        jdbc.execute("""
            create table if not exists business_workspace_role_note (
                tenant_id varchar(64) not null,
                role_id varchar(64) not null,
                panel varchar(16) not null,
                title varchar(120) not null,
                body varchar(4000) not null,
                primary key (tenant_id, role_id, panel),
                foreign key (role_id) references sys_role(id) on delete cascade
            )
            """);
        String manifest = new ClassPathResource("workspace/business.json").getContentAsString(StandardCharsets.UTF_8);
        jdbc.update("""
            insert into workspace_registry(id,status,access_permission_code,manifest_json,default_workspace,version,created_at,updated_at)
            select 'business','enabled','workspace:business:access',?,false,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
            where not exists(select 1 from workspace_registry where id='business')
            """, manifest);
        jdbc.update("""
            insert into sys_permission(id,permission_code,permission_name,permission_type,status,sort_order,created_at,updated_at)
            select 'ws-business','workspace:business:access','业务工作空间访问','menu','enabled',25,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
            where not exists(select 1 from sys_permission where permission_code='workspace:business:access')
            """);
        jdbc.update("""
            insert into sys_permission(id,permission_code,permission_name,permission_type,status,sort_order,created_at,updated_at)
            select 'ws-business-knowledge','workspace:business:knowledge','业务知识检索','api','enabled',26,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
            where not exists(select 1 from sys_permission where permission_code='workspace:business:knowledge')
            """);
        jdbc.update("""
            insert into sys_menu(id,parent_id,menu_code,menu_name,menu_type,route_path,icon,sort_order,status,created_at,updated_at)
            select 'ws-business-home',null,'businessHome','智能对话','menu','/index.html#/w/business/businessHome',
                'message-circle',25,'enabled',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
            where not exists(select 1 from sys_menu where menu_code='businessHome')
            """);
        jdbc.update("""
            insert into sys_menu_permission(id,menu_id,permission_id,created_at,updated_at)
            select 'ws-business-menu-access',m.id,p.id,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
            from sys_menu m join sys_permission p on p.permission_code='workspace:business:access'
            where m.menu_code='businessHome' and not exists(select 1 from sys_menu_permission mp
                where mp.menu_id=m.id and mp.permission_id=p.id)
            """);

        // Bootstrap only existing tenant super administrators, once. Role changes remain explicit.
        if (!jdbc.queryForList("select id from sys_permission where id='ws-business-seeded'").isEmpty()) return;
        for (var role : jdbc.queryForList("""
            select r.id,r.tenant_id,p.id as permission_id from sys_role r
            join sys_permission p on p.permission_code='workspace:business:access'
            where r.role_code='SUPER_ADMIN' and r.status='enabled'
              and not exists(select 1 from sys_role_permission rp
                where rp.role_id=r.id and rp.tenant_id=r.tenant_id and rp.permission_id=p.id)
            """)) {
            String id = UUID.nameUUIDFromBytes(("workspace:business:" + role.get("id")).getBytes(StandardCharsets.UTF_8)).toString();
            jdbc.update("""
                insert into sys_role_permission(id,tenant_id,role_id,permission_id,created_at,updated_at)
                values (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, role.get("tenant_id"), role.get("id"), role.get("permission_id"));
        }
        jdbc.update("""
            insert into sys_permission(id,permission_code,permission_name,permission_type,status,sort_order,created_at,updated_at)
            values ('ws-business-seeded','system:business-workspace:v1','业务工作空间初始化 V1','internal','enabled',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """);
    }
}
