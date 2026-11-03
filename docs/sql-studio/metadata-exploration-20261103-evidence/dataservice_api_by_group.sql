-- ============================================================
-- 查询指定项目、指定分组下的数据服务 API SQL 语句
-- 数据库: LiveDOP (MySQL)
--
-- 关联关系说明 (均为推断的等值连接, 未声明外键约束):
--   ld_dataservice_api.group_id   = ld_dataservice_group.id
--   ld_dataservice_api.project_id = ld_project.id
--   ld_dataservice_group.project_id = ld_project.id
--
-- 参数说明:
--   :projectId  — 项目 ID (ld_project.id)
--   :groupId    — 分组 ID (ld_dataservice_group.id)
-- ============================================================

SELECT
    a.id              AS api_id,
    a.api_id          AS api_code,
    a.api_name,
    a.service_name,
    a.project_id,
    a.group_id,
    g.name            AS group_name,
    g.parent_id_path  AS group_path,
    p.name            AS project_name,
    a.sql_content,
    a.type            AS api_type,
    a.state           AS publish_state,
    a.description     AS api_comment,
    a.create_time,
    a.update_time
FROM LiveDOP.ld_dataservice_api a
INNER JOIN LiveDOP.ld_dataservice_group g
        ON a.group_id = g.id                       -- 推断连接: API -> 分组
INNER JOIN LiveDOP.ld_project p
        ON a.project_id = p.id                     -- 推断连接: API -> 项目
WHERE a.project_id = :projectId                    -- 绑定参数: 项目 ID
  AND a.group_id   = :groupId                      -- 绑定参数: 分组 ID
ORDER BY a.api_name;
