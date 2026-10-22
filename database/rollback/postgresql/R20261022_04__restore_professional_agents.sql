-- Stop the new application first. Restore the previous application only after this transaction succeeds.
BEGIN;
DO $$
DECLARE batch text; item record; table_name text;
BEGIN
 SELECT batch_id INTO batch FROM runtime_product_retirement_archive
 WHERE record_type='MARKER' AND record_id='professional-agents-v1' ORDER BY created_at DESC LIMIT 1;
 IF batch IS NOT NULL THEN
  IF EXISTS (SELECT 1 FROM runtime_product_retirement_archive a JOIN workspace_registry w ON w.id=a.record_id
       WHERE a.batch_id=batch AND a.record_type='workspace_registry'
       AND w.manifest_json<>a.replacement_payload AND w.manifest_json<>(a.saved_payload::jsonb->>'manifest_json')) THEN
   RAISE EXCEPTION 'Workspace changed after retirement; merge its archived manifest manually before rollback';
  END IF;
  FOREACH table_name IN ARRAY ARRAY['sys_permission','sys_menu','sys_role_permission','sys_menu_permission'] LOOP
   EXECUTE format('INSERT INTO public.%I SELECT (jsonb_populate_record(NULL::public.%I,saved_payload::jsonb)).* FROM runtime_product_retirement_archive WHERE batch_id=$1 AND record_type=$2 ON CONFLICT(id) DO NOTHING',table_name,table_name)
   USING batch,table_name;
  END LOOP;
  UPDATE workspace_registry w SET manifest_json=a.saved_payload::jsonb->>'manifest_json',version=w.version+1,updated_at=CURRENT_TIMESTAMP
   FROM runtime_product_retirement_archive a WHERE a.batch_id=batch AND a.record_type='workspace_registry' AND a.record_id=w.id;
  UPDATE sys_permission p SET permission_name=a.saved_payload::jsonb->>'permission_name',resource_path=a.saved_payload::jsonb->>'resource_path'
   FROM runtime_product_retirement_archive a WHERE a.batch_id=batch AND a.record_type='sys_permission' AND p.id=a.record_id
    AND p.permission_code='system:agent-registry:manage' AND p.permission_name='Runtime 运行配置管理';
  DELETE FROM runtime_product_retirement_archive WHERE batch_id=batch AND record_type='MARKER';
 END IF;
 FOREACH table_name IN ARRAY ARRAY['agent_compute_definition','agent_provider_health','agent_a2a_task_link'] LOOP
  IF to_regclass('runtime_os_archive.'||table_name) IS NOT NULL THEN
   IF to_regclass('public.'||table_name) IS NOT NULL THEN RAISE EXCEPTION 'Public table % already exists; resolve collision first',table_name; END IF;
   EXECUTE format('ALTER TABLE runtime_os_archive.%I SET SCHEMA public',table_name);
  END IF;
 END LOOP;
END $$;
COMMIT;
