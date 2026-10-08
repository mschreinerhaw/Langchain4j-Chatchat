create table if not exists agent_tool_intent_binding (
    input_key varchar(128) not null primary key,
    requested_tool_name varchar(128) not null,
    remote_tool_name varchar(128) not null,
    local_tool_name_suffix varchar(128) not null,
    enabled boolean not null
);

insert into agent_tool_intent_binding
    (input_key, requested_tool_name, remote_tool_name, local_tool_name_suffix, enabled)
values ('webSearch', 'web_search', 'web_search', '_web_search', true);

insert into agent_tool_intent_binding
    (input_key, requested_tool_name, remote_tool_name, local_tool_name_suffix, enabled)
values ('documentWorkflow', 'document_search', '', '', true);
