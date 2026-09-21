-- Apply to each existing API or MCP H2 database that contains knowledge_ir_unit.
alter table knowledge_ir_unit alter column source_section text;
alter table knowledge_ir_unit alter column title text;
