create table if not exists mcp_tool_runtime_capability (
    remote_tool_name varchar(128) not null primary key,
    batch_execution boolean not null,
    template_execution boolean not null
);

insert into mcp_tool_runtime_capability (remote_tool_name, batch_execution, template_execution) values
    ('sql_query_execute', true, true),
    ('ssh_linux_execute', true, true),
    ('linux_command_execute', true, true),
    ('api_query_execute', true, true),
    ('api_template_execute', true, true),
    ('python_template_execute', true, true),
    ('http_request_execute', true, true);
