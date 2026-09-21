-- Apply to each existing API or MCP MySQL database that contains knowledge_ir_unit.
alter table knowledge_ir_unit
    modify column source_section text null,
    modify column title text null;
