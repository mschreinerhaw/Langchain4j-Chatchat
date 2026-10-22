-- Run as DBA with API stopped, after pg_dump. Product metadata is archived by RetiredAnalysisMigration.
BEGIN;
CREATE SCHEMA IF NOT EXISTS runtime_os_archive;
REVOKE ALL ON SCHEMA runtime_os_archive FROM PUBLIC;
DO $$
DECLARE source_name text; relation_oid oid; application_owner text;
BEGIN
 FOREACH source_name IN ARRAY ARRAY['agent_compute_definition','agent_provider_health','agent_a2a_task_link'] LOOP
  relation_oid := to_regclass('public.' || source_name);
  IF relation_oid IS NULL THEN CONTINUE; END IF;
  IF to_regclass('runtime_os_archive.' || source_name) IS NOT NULL THEN
   RAISE EXCEPTION 'Archive collision for %; inspect both copies before migration', source_name;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE contype='f' AND confrelid=relation_oid
       AND conrelid NOT IN (SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
           WHERE n.nspname='public' AND c.relname IN ('agent_compute_definition','agent_provider_health','agent_a2a_task_link'))) THEN
   RAISE EXCEPTION 'Referenced legacy table %; resolve live foreign keys before archiving', source_name;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_depend d JOIN pg_rewrite r ON r.oid=d.objid
       JOIN pg_class v ON v.oid=r.ev_class WHERE d.refobjid=relation_oid AND v.relkind IN ('v','m')) THEN
   RAISE EXCEPTION 'A view depends on %; review it before archiving', source_name;
  END IF;
 END LOOP;
 FOREACH source_name IN ARRAY ARRAY['agent_compute_definition','agent_provider_health','agent_a2a_task_link'] LOOP
  IF to_regclass('public.' || source_name) IS NOT NULL THEN
   EXECUTE format('ALTER TABLE public.%I SET SCHEMA runtime_os_archive',source_name);
  END IF;
 END LOOP;
END $$;
CREATE TABLE IF NOT EXISTS runtime_product_retirement_archive (
 batch_id varchar(64) not null, record_type varchar(64) not null, record_id varchar(128) not null,
 saved_payload text not null, replacement_payload text,
 created_at timestamp not null default CURRENT_TIMESTAMP,
 PRIMARY KEY(batch_id,record_type,record_id)
);
DO $$
DECLARE application_owner text;
BEGIN
 SELECT pg_get_userbyid(relowner) INTO STRICT application_owner FROM pg_class WHERE oid='public.workspace_registry'::regclass;
 EXECUTE format('ALTER TABLE public.runtime_product_retirement_archive OWNER TO %I',application_owner);
END $$;
COMMIT;
