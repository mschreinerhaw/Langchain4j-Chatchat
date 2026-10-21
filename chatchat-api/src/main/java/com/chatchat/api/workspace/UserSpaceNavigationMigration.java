package com.chatchat.api.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Consolidates the two built-in menu entries without replacing customized workspace manifests. */
@Component @RequiredArgsConstructor
public class UserSpaceNavigationMigration {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    @EventListener(ApplicationReadyEvent.class) @Order(130) @Transactional
    public void migrate() throws java.io.IOException {
        for(String original:jdbc.queryForList("select manifest_json from workspace_registry where id='data-science'",String.class)){
            var manifest=(ObjectNode)json.readTree(original);var pages=manifest.withArray("pages");boolean changed=false;
            for(int i=pages.size()-1;i>=0;i--)if("dataScienceScripts".equals(pages.get(i).path("id").asText())
                && "dataScienceScripts".equals(pages.get(i).path("componentKey").asText())){pages.remove(i);changed=true;}
            if(changed){
                if("dataScienceScripts".equals(manifest.path("entryPage").asText()))manifest.put("entryPage","dataScienceData");
                manifest.put("configVersion",Math.max(5,manifest.path("configVersion").asInt()+1));
                jdbc.update("update workspace_registry set manifest_json=?,version=version+1,updated_at=CURRENT_TIMESTAMP where id='data-science' and manifest_json=?",json.writeValueAsString(manifest),original);
            }
        }
        jdbc.update("update sys_menu set menu_name=?,icon='folder',updated_at=CURRENT_TIMESTAMP where menu_code='dataScienceData' and menu_name in (?,?)","用户空间","我的数据","用户空间");
        jdbc.update("update sys_menu set status='disabled',updated_at=CURRENT_TIMESTAMP where menu_code='dataScienceScripts' and menu_name=? and status='enabled'","我的脚本");
    }
}
