package com.chatchat.api.datascience.sql;

import com.chatchat.common.kernel.KernelDataScope;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Missing context is resolved only through the existing owner-bound MCP discovery service. */
@Service
public class SqlStudioReferenceResolver {
    private final ObjectProvider<SqlStudioMetadataService> metadata;
    private final SqlGlotToolchain parser;
    public SqlStudioReferenceResolver(ObjectProvider<SqlStudioMetadataService> metadata, SqlGlotToolchain parser) {
        this.metadata=metadata; this.parser=parser;
    }
    @SuppressWarnings("unchecked")
    public List<Map<String,Object>> resolve(KernelDataScope scope, SqlStudioMetadataService.Context context,
            String dialect, List<SqlOutputBundle.Candidate> files, List<Map<String,Object>> cached) {
        var syntax=parser.validate(dialect,files,Map.of());
        if (!Boolean.TRUE.equals(syntax.get("valid")))
            throw new IllegalArgumentException("SQL syntax is invalid: " + syntax.get("diagnostics"));
        var tables=new ArrayList<>(cached);
        Object raw=syntax.get("references");
        if (!(raw instanceof List<?> references)) return tables;
        if (references.size()>64) throw new IllegalArgumentException("At most 64 referenced tables per proposal");
        for (Object item:references) {
            var ref=(Map<String,Object>)item;
            String name=Objects.toString(ref.get("tableName"),""), namespace=Objects.toString(ref.get("schema"),""), catalog=Objects.toString(ref.get("database"),"");
            boolean twoPartDatabase="mysql".equals(dialect) || "hive".equals(dialect) || "spark".equals(dialect);
            // Recheck referenced cached objects too: a previous snapshot cannot preserve revoked grants.
            var found=new ArrayList<Map<String,Object>>();
            boolean eligible=false;
            for (var binding:context.bindings()) {
                if (!catalog.isEmpty() && binding.database()!=null && !catalog.equals(binding.database())) continue;
                if (!namespace.isEmpty() && binding.schema()!=null && !namespace.equals(binding.schema())) continue;
                // A two-part MySQL identifier is database.table, represented as db by SQLGlot.
                if (twoPartDatabase && !namespace.isEmpty() && binding.schema()==null && binding.database()!=null && !namespace.equals(binding.database())) continue;
                eligible=true;
                var args=new LinkedHashMap<String,Object>();
                args.put("operation","DESCRIBE");args.put("tableName",name);args.put("page",1);args.put("pageSize",100);
                if (!catalog.isEmpty()) args.put("database",catalog);
                if (!namespace.isEmpty()) args.put(twoPartDatabase && binding.schema()==null ? "database" : "schema",namespace);
                var result=metadata.getObject().operateScoped(scope,context,new SqlStudioMetadataService.Operation(binding.bindingId(),args)).metadata();
                if (!"SUCCEEDED".equals(result.get("status"))) throw new IllegalArgumentException("Metadata discovery failed for table: "+name);
                if (result.get("items") instanceof List<?> items) for(Object value:items) {
                    var table=(Map<String,Object>)value;
                    if (!matches(table,ref)) continue;
                    if (!(table.get("columns") instanceof List<?> columns) || columns.isEmpty())
                        throw new SecurityException("Table has no authorized visible columns: "+name);
                    var enriched=new LinkedHashMap<>(table);
                    enriched.put("bindingId",binding.bindingId());enriched.put("discoveryId",context.discoveryId());
                    enriched.put("expectedSnapshotId",Objects.toString(result.get("snapshotId"),""));
                    found.add(enriched);
                }
            }
            if (!eligible) throw new SecurityException("Table namespace is outside the selected database/schema: "+name);
            if (found.isEmpty()) throw new IllegalArgumentException("TABLE_NOT_FOUND: no visible table in the authorized database: "+name);
            if (found.size()!=1) throw new IllegalArgumentException("AMBIGUOUS_TABLE: qualify database/schema for "+name);
            tables.removeIf(t->matches(t,ref));
            tables.add(found.get(0));
        }
        return List.copyOf(tables);
    }
    static boolean matches(Map<String,Object> table,Map<String,Object> ref) {
        boolean quoted=Boolean.TRUE.equals(ref.get("quoted"));
        String name=Objects.toString(ref.get("tableName"),""), actual=Objects.toString(table.get("tableName"),"");
        if (!(quoted?name.equals(actual):name.equalsIgnoreCase(actual))) return false;
        String catalog=Objects.toString(ref.get("database"),""), namespace=Objects.toString(ref.get("schema"),"");
        return (catalog.isEmpty()||catalog.equals(table.get("database")))
            && (namespace.isEmpty()||namespace.equals(table.get("schema"))||namespace.equals(table.get("database")));
    }
}
