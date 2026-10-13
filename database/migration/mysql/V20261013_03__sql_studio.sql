-- SQL Studio additive schema/config migration; no production execution in this round.
CREATE TABLE IF NOT EXISTS ds_sql_project (
    id VARCHAR(64) PRIMARY KEY, name VARCHAR(180) NOT NULL, dialect VARCHAR(32) NOT NULL,
    session_id VARCHAR(64) NOT NULL, tenant_id VARCHAR(64) NOT NULL, owner_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS ds_sql_file (
    id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL,
    path VARCHAR(240) NOT NULL, path_key VARCHAR(240) NOT NULL, kind VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL, version INTEGER NOT NULL, original_base64 LONGTEXT, source_task_id VARCHAR(64),
    updated_at TIMESTAMP NOT NULL, CONSTRAINT uk_sql_project_path UNIQUE(project_id, path_key)
);
CREATE TABLE IF NOT EXISTS ds_sql_file_version (
    id VARCHAR(64) PRIMARY KEY, file_id VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL, content LONGTEXT NOT NULL, original_base64 LONGTEXT, source_task_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL, CONSTRAINT uk_sql_file_version UNIQUE(file_id, version)
);
CREATE TABLE IF NOT EXISTS ds_sql_generation (
    id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL,
    context_json LONGTEXT NOT NULL, base_versions_json LONGTEXT NOT NULL, task_id VARCHAR(64),
    applied BOOLEAN NOT NULL, created_at TIMESTAMP NOT NULL
);

-- Only upgrade the unmodified built-in V2 manifest; custom manifests remain untouched.
UPDATE workspace_registry SET manifest_json='{"schemaVersion":"workspace-manifest.v1","configVersion":3,"workspaceId":"data-science","appVersion":"1.0.0","name":"数据科学","icon":"flask","description":"Python 开发、SQL 工程、数据与脚本","entryPage":"dataScienceEnvironment","layoutType":"studio","pages":[{"id":"dataScience","menuCode":"dataScience","componentKey":"dataScience","props":{}},{"id":"dataScienceEnvironment","menuCode":"dataScienceEnvironment","componentKey":"dataScienceEnvironment","props":{}},{"id":"dataScienceDevelop","menuCode":"dataScienceDevelop","componentKey":"dataScienceDevelop","props":{}},{"id":"dataScienceData","menuCode":"dataScienceData","componentKey":"dataScienceData","props":{}},{"id":"dataScienceScripts","menuCode":"dataScienceScripts","componentKey":"dataScienceScripts","props":{}},{"id":"sqlStudio","menuCode":"sqlStudio","componentKey":"sqlStudio","props":{}}],"requiredCapabilities":["capability:data-science"],"optionalCapabilities":[],"defaultSkillIds":[]}',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id='data-science' AND manifest_json='{"schemaVersion":"workspace-manifest.v1","configVersion":2,"workspaceId":"data-science","appVersion":"1.0.0","name":"数据科学","icon":"flask","description":"Python 环境、开发、数据与脚本","entryPage":"dataScienceEnvironment","layoutType":"studio","pages":[{"id":"dataScience","menuCode":"dataScience","componentKey":"dataScience","props":{}},{"id":"dataScienceEnvironment","menuCode":"dataScienceEnvironment","componentKey":"dataScienceEnvironment","props":{}},{"id":"dataScienceDevelop","menuCode":"dataScienceDevelop","componentKey":"dataScienceDevelop","props":{}},{"id":"dataScienceData","menuCode":"dataScienceData","componentKey":"dataScienceData","props":{}},{"id":"dataScienceScripts","menuCode":"dataScienceScripts","componentKey":"dataScienceScripts","props":{}}],"requiredCapabilities":["capability:data-science"],"optionalCapabilities":[{"id":"sql.generation","state":"reserved","name":"SQL Studio"}],"defaultSkillIds":[]}';
INSERT INTO sys_menu (id,parent_id,menu_code,menu_name,menu_type,route_path,icon,sort_order,status,created_at,updated_at)
SELECT 'sql-studio-menu',parent.id,'sqlStudio','SQL Studio','menu','/index.html#sqlStudio','database',29,'enabled',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM sys_menu parent WHERE parent.menu_code='dataScience' AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_code='sqlStudio');
INSERT INTO sys_menu_permission (id,menu_id,permission_id,created_at,updated_at)
SELECT 'sql-studio-menu-permission',m.id,p.id,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM sys_menu m JOIN sys_permission p ON p.permission_code='capability:data-science'
WHERE m.menu_code='sqlStudio' AND NOT EXISTS (SELECT 1 FROM sys_menu_permission mp WHERE mp.menu_id=m.id AND mp.permission_id=p.id);
