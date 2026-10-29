package com.chatchat.api.datascience;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Idempotent classification from persisted application provenance, never from editable titles. */
@Component @RequiredArgsConstructor
public class StudioSessionMigration {
    private final JdbcTemplate jdbc;
    @EventListener(ApplicationReadyEvent.class) @Order(120) @Transactional
    public void migrate() {
        jdbc.update("""
            update chat_session set source_channel='SQL_STUDIO'
            where coalesce(source_channel,'PLATFORM_UI')='PLATFORM_UI' and (
              exists (select 1 from ds_sql_generation_scope g where g.session_id=chat_session.session_id
                and g.tenant_id=chat_session.tenant_id and g.owner_id=chat_session.user_id)
              or exists (select 1 from ds_sql_project p where p.session_id=chat_session.session_id
                and p.tenant_id=chat_session.tenant_id and p.owner_id=chat_session.user_id))
            """);
        // These exact labels are written by StudioTaskHistory, not inferred from user titles.
        jdbc.update("""
            update chat_session set source_channel=case when agent_name='PythonStudio'
              then 'PYTHON_STUDIO' else 'SQL_STUDIO' end
            where coalesce(source_channel,'PLATFORM_UI')='PLATFORM_UI'
              and mode='llm_chat' and agent_name in ('PythonStudio','SQLStudio')
            """);
    }
}
