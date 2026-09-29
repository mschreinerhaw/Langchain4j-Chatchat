package com.chatchat.api.enterprise.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

/** Bounded, read-only catalog and relationship queries; never materializes the role/skill matrix. */
@Service
@RequiredArgsConstructor
public class SkillRoleQueryService {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String CATALOG = """
        (select id, label as name, description, 'AGENT_SKILL' as resource_type, market_status as status,
          concat(id, ' ', label, ' ', coalesce(description,''), ' ', coalesce(skill_tags_json,''),
            ' ', coalesce(usage_scenarios_json,'')) as search_text from skill_config
         union all
         select id, name, description, 'SKILL' as resource_type, status, search_text
         from ds_domain_skill where tenant_id = :tenant or builtin = true) s
        """;
    private static final String ACTIVE_BINDING = """
        b.tenant_id = :tenant and b.enabled = true
        and (b.effective_time is null or b.effective_time <= current_timestamp)
        and (b.expire_time is null or b.expire_time > current_timestamp)
        """;
    private static final String ACTIVE_GRANT = """
        g.tenant_id = :tenant and g.principal_type = 'ROLE' and g.enabled = true
        and g.effect = 'ALLOW' and (g.expires_at is null or g.expires_at > current_timestamp)
        """;
    private static final String NO_DENY = """
        not exists (select 1 from resource_grant d where d.tenant_id = :tenant
          and d.principal_type = 'ROLE' and d.principal_id = r.id and d.enabled = true
          and d.effect = 'DENY' and (d.expires_at is null or d.expires_at > current_timestamp)
          and d.resource_type = s.resource_type and (d.resource_id = s.id or d.resource_id = '*')
          and (d.agent_id is null or d.agent_id = '' or d.agent_id = g.agent_id))
        """;

    @Transactional(readOnly = true)
    public Result query(String tenant, String view, String query, String type, String roleId,
                        String skillId, int requestedPage, int requestedSize) {
        if (!List.of("roles", "skills", "relations").contains(view)) throw new IllegalArgumentException("Invalid view");
        if (!List.of("", "SKILL", "AGENT_SKILL").contains(type)) throw new IllegalArgumentException("Invalid resourceType");
        int size = Math.max(10, Math.min(100, requestedSize));
        int page = Math.max(1, requestedPage);
        String keyword = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (keyword.length() > 200) throw new IllegalArgumentException("Search query is too long");
        var params = new MapSqlParameterSource().addValue("tenant", tenant).addValue("type", type)
            .addValue("role", roleId).addValue("skill", skillId)
            .addValue("query", "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        String from;
        String select;
        String order;
        if (view.equals("roles")) {
            select = "id, role_name as name, role_code as code, description, status";
            from = "sys_role where tenant_id = :tenant and lower(concat(role_name,' ',role_code,' ',coalesce(description,''))) like :query escape '!'";
            order = "role_name, id";
        } else if (view.equals("skills")) {
            select = "s.id, s.name, s.description, s.resource_type, s.status";
            from = CATALOG + " where (:type = '' or s.resource_type = :type) and lower(s.search_text) like :query escape '!'";
            order = "s.name, s.resource_type, s.id";
        } else {
            if ((roleId == null || roleId.isBlank()) == (skillId == null || skillId.isBlank()))
                throw new IllegalArgumentException("Select exactly one role or skill");
            if (skillId != null && !skillId.isBlank() && type.isBlank())
                throw new IllegalArgumentException("Skill resourceType is required");
            String filter = roleId != null && !roleId.isBlank() ? "r.id = :role" : "s.id = :skill";
            filter += " and (:type = '' or s.resource_type = :type)";
            filter += " and lower(concat(s.search_text, ' ', r.role_name, ' ', r.role_code)) like :query escape '!'";
            String columns = "r.id as role_id, r.role_name, r.role_code, s.id as skill_id, s.name as skill_name, s.resource_type, s.status";
            String shared = "select " + columns + ", coalesce(g.agent_id,'') as agent_id, coalesce(a.label,'') as agent_name, '资源授权' as source "
                + "from sys_role r join resource_grant g on g.principal_id = r.id join " + CATALOG
                + " on g.resource_type = s.resource_type and (g.resource_id = s.id or g.resource_id = '*') "
                + "left join skill_config a on a.id = g.agent_id where r.tenant_id = :tenant and r.status = 'enabled' and "
                + ACTIVE_GRANT + " and " + NO_DENY
                + " and (g.agent_id is null or g.agent_id = '' or exists (select 1 from role_agent_binding b where "
                + ACTIVE_BINDING + " and b.role_id = r.id and b.agent_id = g.agent_id)) and " + filter;
            String bound = "select " + columns + ", '' as agent_id, '' as agent_name, '角色绑定' as source "
                + "from sys_role r join role_agent_binding b on b.role_id = r.id join " + CATALOG
                + " on s.id = b.agent_id and s.resource_type = 'AGENT_SKILL' "
                + "left join resource_grant g on 1 = 0 where r.tenant_id = :tenant and r.status = 'enabled' and "
                + ACTIVE_BINDING + " and " + NO_DENY + " and " + filter;
            from = "(" + shared + " union " + bound + ") relation_rows";
            select = "*";
            order = "skill_name, resource_type, skill_id, role_name, role_id, agent_id, source";
        }
        Long count = jdbc.queryForObject("select count(*) from " + from, params, Long.class);
        long total = count == null ? 0 : count;
        int pages = (int) Math.max(1, (total + size - 1) / size);
        page = Math.min(page, pages);
        params.addValue("limit", size).addValue("offset", (long) (page - 1) * size);
        List<Map<String, Object>> items = jdbc.query("select " + select + " from " + from
            + " order by " + order + " limit :limit offset :offset", params, (rs, row) -> {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                var meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) item.put(meta.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), rs.getObject(i));
                return item;
            });
        return new Result(items, total, page, size, pages);
    }

    public record Result(List<Map<String, Object>> items, long total, int page, int pageSize, int totalPages) {}
}
