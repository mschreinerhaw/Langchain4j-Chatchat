-- Stop API and take pg_dump first. No identity, role membership or business grants are changed.
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('permission-workspace-ux-v1'));
DO $$
DECLARE batch text:=gen_random_uuid()::text; item record; payload text; replacement text;
 manifest jsonb; pages jsonb; root_id text; settings_id text; target_permission_id text; binding_id text;
BEGIN
 IF EXISTS(SELECT 1 FROM runtime_product_retirement_archive WHERE record_type='MARKER' AND record_id='permission-workspace-ux-v1') THEN RETURN; END IF;
 IF EXISTS(SELECT 1 FROM sys_menu WHERE parent_id IN (SELECT id FROM sys_menu WHERE menu_code IN ('systemSkillRoles','permissionTools'))) THEN
  RAISE EXCEPTION 'Retired permission menus have custom children; reparent before migration';
 END IF;
 FOR item IN SELECT t.id,row_to_json(t)::text saved FROM sys_menu t WHERE menu_code IN ('systemUsers','systemOrganizations','systemRoles','systemResources','systemLogins','systemSkillRoles','permissionTools','workspaces','permissionsRoot') LOOP
  INSERT INTO runtime_product_retirement_archive(batch_id,record_type,record_id,saved_payload) VALUES(batch,'sys_menu',item.id,item.saved);
 END LOOP;
 FOR item IN SELECT t.id,row_to_json(t)::text saved FROM sys_menu_permission t WHERE menu_id IN (SELECT id FROM sys_menu WHERE menu_code IN ('systemSkillRoles','permissionTools','workspaces','permissionsRoot')) LOOP
  INSERT INTO runtime_product_retirement_archive(batch_id,record_type,record_id,saved_payload) VALUES(batch,'sys_menu_permission',item.id,item.saved);
 END LOOP;
 FOR item IN SELECT t.*,row_to_json(t)::text saved FROM workspace_registry t WHERE id IN ('permissions','zhice') LOOP
  manifest:=item.manifest_json::jsonb;
  SELECT coalesce(jsonb_agg(p.value ORDER BY p.ordinality),'[]'::jsonb) INTO pages FROM jsonb_array_elements(manifest->'pages') WITH ORDINALITY p
   WHERE coalesce(p.value->>'id','') NOT IN ('systemSkillRoles','permissionTools') AND coalesce(p.value->>'componentKey','') NOT IN ('systemSkillRoles','permissionTools')
    AND (item.id<>'zhice' OR (coalesce(p.value->>'id','') NOT IN ('system','systemUsers','systemOrganizations','systemRoles','systemResources','systemLogins') AND coalesce(p.value->>'componentKey','') NOT IN ('system','systemUsers','systemOrganizations','systemRoles','systemResources','systemLogins')));
  IF item.id='permissions' THEN
   manifest:=jsonb_set(jsonb_set(manifest,'{layoutType}','"management"'),'{name}','"权限管理"');
  ELSIF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(pages) p WHERE p->>'id'='workspaces') THEN
   pages:=pages||'[{"id":"workspaces","menuCode":"workspaces","componentKey":"workspaces","props":{}}]'::jsonb;
  END IF;
  manifest:=jsonb_set(manifest,'{pages}',pages);
  IF manifest->>'entryPage' IN ('systemSkillRoles','permissionTools') THEN manifest:=jsonb_set(manifest,'{entryPage}','"systemResources"'); END IF;
  replacement:=jsonb_set(manifest,'{configVersion}',to_jsonb(coalesce((manifest->>'configVersion')::int,0)+1))::text;
  INSERT INTO runtime_product_retirement_archive(batch_id,record_type,record_id,saved_payload,replacement_payload) VALUES(batch,'workspace_registry',item.id,item.saved,replacement);
  UPDATE workspace_registry SET manifest_json=replacement,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=item.id;
 END LOOP;
 SELECT id INTO root_id FROM sys_menu WHERE menu_code='permissionsRoot';
 IF root_id IS NULL THEN
  root_id:='permission-workspace-root';
  INSERT INTO sys_menu(id,menu_code,menu_name,menu_type,icon,sort_order,status,created_at,updated_at) VALUES(root_id,'permissionsRoot','权限管理','directory','shield-check',40,'enabled',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
  INSERT INTO runtime_product_retirement_archive(batch_id,record_type,record_id,saved_payload) VALUES(batch,'INSERTED_sys_menu',root_id,'{}');
 END IF;
 UPDATE sys_menu SET parent_id=root_id,menu_name=CASE menu_code WHEN 'systemUsers' THEN '用户管理' WHEN 'systemOrganizations' THEN '组织管理' WHEN 'systemRoles' THEN '角色权限' WHEN 'systemResources' THEN '资源授权' ELSE '安全审计' END,
  route_path='/index.html#/w/permissions/'||menu_code,updated_at=CURRENT_TIMESTAMP WHERE menu_code IN ('systemUsers','systemOrganizations','systemRoles','systemResources','systemLogins');
 DELETE FROM sys_menu_permission WHERE menu_id IN (SELECT id FROM sys_menu WHERE menu_code IN ('systemSkillRoles','permissionTools'));
 DELETE FROM sys_menu WHERE menu_code IN ('systemSkillRoles','permissionTools');
 SELECT id INTO STRICT settings_id FROM sys_menu WHERE menu_code='workspaces';
 UPDATE sys_menu SET menu_name='工作空间设置',route_path='/index.html#/w/zhice/workspaces',status='enabled',updated_at=CURRENT_TIMESTAMP WHERE id=settings_id;
 DELETE FROM sys_menu_permission WHERE id='ws-selection-access' AND menu_id=settings_id AND permission_id IN (SELECT id FROM sys_permission WHERE permission_code='workspace:zhice:access');
 FOR item IN SELECT * FROM (VALUES(root_id,'workspace:permissions:access','permission-ux-root-access'),(settings_id,'system:workspaces:manage','permission-ux-settings-access')) AS x(menu_id,code,new_id) LOOP
  SELECT id INTO STRICT target_permission_id FROM sys_permission WHERE permission_code=item.code;
  IF NOT EXISTS(SELECT 1 FROM sys_menu_permission mp WHERE mp.menu_id=item.menu_id AND mp.permission_id=target_permission_id) THEN
   INSERT INTO sys_menu_permission(id,menu_id,permission_id,created_at,updated_at) VALUES(item.new_id,item.menu_id,target_permission_id,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
   INSERT INTO runtime_product_retirement_archive(batch_id,record_type,record_id,saved_payload,replacement_payload) SELECT batch,'INSERTED_sys_menu_permission',t.id,'{}',row_to_json(t)::text FROM sys_menu_permission t WHERE t.id=item.new_id;
  END IF;
 END LOOP;
 UPDATE runtime_product_retirement_archive a SET replacement_payload=row_to_json(t)::text FROM sys_menu t WHERE a.batch_id=batch AND a.record_type IN ('sys_menu','INSERTED_sys_menu') AND a.record_id=t.id;
 INSERT INTO runtime_product_retirement_archive(batch_id,record_type,record_id,saved_payload) VALUES(batch,'MARKER','permission-workspace-ux-v1','{}');
END $$;
COMMIT;
