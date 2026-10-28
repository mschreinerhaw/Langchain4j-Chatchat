package com.chatchat.mcpserver.sql.metadata;

/** One JDBC imported-key column pair; sequence and constraint identity preserve composite keys. */
public record MetadataForeignKey(String name,String column,String referencedDatabase,String referencedSchema,
                                 String referencedTable,String referencedColumn,int keySequence) { }
