-- Apply to each existing PostgreSQL database that contains knowledge_ir_unit.
alter table knowledge_ir_unit alter column source_section TYPE text;
alter table knowledge_ir_unit alter column title TYPE text;
