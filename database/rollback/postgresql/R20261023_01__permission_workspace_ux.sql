-- Stop the new API first. Subsequent administrator changes block rollback rather than being overwritten.
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('permission-workspace-ux-v1'));
DO $$
DECLARE batch text; item record;
BEGIN
 SELECT batch_id INTO batch FROM runtime_product_retirement_archive WHERE record_type='MARKER' AND record_id='permission-workspace-ux-v1' ORDER BY created_at DESC LIMIT 1;
 IF batch IS NULL THEN RETURN; END IF;
 IF EXISTS(SELECT 1 FROM runtime_product_retirement_archive a JOIN workspace_registry w ON a.record_id=w.id WHERE a.batch_id=batch AND a.record_type='workspace_registry'
   AND w.manifest_json<>a.replacement_payload AND w.manifest_json<>(a.saved_payload::jsonb->>'manifest_json')) THEN RAISE EXCEPTION 'Workspace changed after UX migration; merge archived changes before rollback'; END IF;
 IF EXISTS(SELECT 1 FROM runtime_product_retirement_archive a JOIN sys_menu m ON m.id=a.record_id WHERE a.batch_id=batch AND a.record_type IN ('sys_menu','INSERTED_sys_menu')
   AND a.replacement_payload IS NOT NULL AND (to_jsonb(m)-'updated_at'-'created_at')<>(a.replacement_payload::jsonb-'updated_at'-'created_at')) THEN RAISE EXCEPTION 'Menu changed after UX migration; review before rollback'; END IF;
 IF EXISTS(SELECT 1 FROM sys_menu child JOIN runtime_product_retirement_archive root ON root.record_id=child.parent_id AND root.batch_id=batch AND root.record_type='INSERTED_sys_menu'
   WHERE NOT EXISTS(SELECT 1 FROM runtime_product_retirement_archive a WHERE a.batch_id=batch AND a.record_type='sys_menu' AND a.record_id=child.id)) THEN RAISE EXCEPTION 'New menu has custom children; rollback requires review'; END IF;
 IF EXISTS(SELECT 1 FROM sys_menu_permission mp JOIN runtime_product_retirement_archive root ON root.record_id=mp.menu_id AND root.batch_id=batch AND root.record_type='INSERTED_sys_menu'
   WHERE NOT EXISTS(SELECT 1 FROM runtime_product_retirement_archive a WHERE a.batch_id=batch AND a.record_type='INSERTED_sys_menu_permission' AND a.record_id=mp.id)) THEN RAISE EXCEPTION 'New menu has custom access bindings; rollback requires review'; END IF;
 DELETE FROM sys_menu_permission mp USING runtime_product_retirement_archive a WHERE a.batch_id=batch AND a.record_type='INSERTED_sys_menu_permission' AND a.record_id=mp.id AND to_jsonb(mp)-'updated_at'-'created_at'=a.replacement_payload::jsonb-'updated_at'-'created_at';
 FOR item IN SELECT saved_payload FROM runtime_product_retirement_archive WHERE batch_id=batch AND record_type='sys_menu' LOOP
  INSERT INTO sys_menu SELECT (jsonb_populate_record(NULL::sys_menu,item.saved_payload::jsonb)).*
   ON CONFLICT(id) DO UPDATE SET parent_id=EXCLUDED.parent_id,menu_code=EXCLUDED.menu_code,menu_name=EXCLUDED.menu_name,
    menu_type=EXCLUDED.menu_type,route_path=EXCLUDED.route_path,icon=EXCLUDED.icon,sort_order=EXCLUDED.sort_order,status=EXCLUDED.status,created_at=EXCLUDED.created_at,updated_at=EXCLUDED.updated_at;
 END LOOP;
 DELETE FROM sys_menu m USING runtime_product_retirement_archive a WHERE a.batch_id=batch AND a.record_type='INSERTED_sys_menu' AND a.record_id=m.id;
 INSERT INTO sys_menu_permission SELECT (jsonb_populate_record(NULL::sys_menu_permission,saved_payload::jsonb)).* FROM runtime_product_retirement_archive WHERE batch_id=batch AND record_type='sys_menu_permission' ON CONFLICT(id) DO NOTHING;
 UPDATE workspace_registry w SET manifest_json=a.saved_payload::jsonb->>'manifest_json',version=w.version+1,updated_at=CURRENT_TIMESTAMP FROM runtime_product_retirement_archive a WHERE a.batch_id=batch AND a.record_type='workspace_registry' AND a.record_id=w.id;
 DELETE FROM runtime_product_retirement_archive WHERE batch_id=batch AND record_type='MARKER';
END $$;
COMMIT;
