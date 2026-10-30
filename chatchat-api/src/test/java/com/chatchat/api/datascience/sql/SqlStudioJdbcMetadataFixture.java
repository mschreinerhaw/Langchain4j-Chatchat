package com.chatchat.api.datascience.sql;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.mcpserver.sql.datasource.*;
import com.chatchat.mcpserver.sql.metadata.*;
import com.chatchat.mcpserver.sql.resolution.TableLocation;
import com.chatchat.mcpserver.search.engine.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual JDBC tables/columns/FKs and persisted Studio ownership; only transport/configuration are fixture adapters. */
public final class SqlStudioJdbcMetadataFixture {
    public final JdbcTemplate jdbc;
    public final SqlStudioMetadataService metadata;
    public final SqlStudioMetadataGateway gateway;
    public final SqlMetadataDiscoveryWorkflow workflow;
    public final SqlDatasourceConfig asset = new SqlDatasourceConfig();
    public boolean authorized=true;
    private final DataSource database;

    public SqlStudioJdbcMetadataFixture(DataSource database,ObjectMapper json) throws Exception {
        this.database=database; jdbc=new JdbcTemplate(database);
        jdbc.execute("CREATE SCHEMA APP");jdbc.execute("CREATE SCHEMA OTHER");
        jdbc.execute("CREATE TABLE APP.CUSTOMERS(ID INTEGER PRIMARY KEY)");
        jdbc.execute("CREATE TABLE APP.ORDERS(ID INTEGER PRIMARY KEY, CUSTOMER_ID INTEGER REFERENCES APP.CUSTOMERS(ID), AMOUNT DECIMAL(18,2))");
        jdbc.execute("CREATE TABLE OTHER.ORDERS(ID INTEGER PRIMARY KEY, OTHER_VALUE INTEGER)");
        jdbc.execute("INSERT INTO APP.CUSTOMERS VALUES (1)");jdbc.execute("INSERT INTO APP.ORDERS VALUES (1,1,25)");
        jdbc.execute("CREATE TABLE ds_sql_project(id VARCHAR PRIMARY KEY,tenant_id VARCHAR,owner_id VARCHAR)");
        jdbc.execute("CREATE TABLE ds_sql_metadata_context(id VARCHAR PRIMARY KEY,project_id VARCHAR,tenant_id VARCHAR,owner_id VARCHAR,purpose VARCHAR,target_dialect VARCHAR,bindings_json CLOB)");
        jdbc.update("INSERT INTO ds_sql_project VALUES ('project','tenant','owner')");
        var binding=new SqlStudioMetadataService.Binding("binding","service","asset","SOURCE","APP","APP","oracle");
        jdbc.update("INSERT INTO ds_sql_metadata_context VALUES ('discovery','project','tenant','owner','ETL','hive',?)",json.writeValueAsString(List.of(binding)));
        asset.setId("asset");asset.setName("JDBC-controlled-source");asset.setDatabaseType("oracle");
        var sources=new SqlDatasourceConfigService(null,null,null,null,null) {
            @Override public List<SqlDatasourceConfig> listEnabled(){return List.of(asset);}
        };
        var indexes=new MetadataIndexService(){
            @Override public MetadataIndex indexFor(SqlDatasourceConfig ignored){return snapshot();}
            @Override public MetadataRefreshResult refreshDatasource(SqlDatasourceConfig ignored){
                var value=snapshot();return new MetadataRefreshResult("asset","oracle",2,value.tables().size(),
                    value.tableColumns().values().stream().mapToInt(List::size).sum(),value.refreshedAtMs(),0,false,null,null);
            }
        };
        var properties=new LuceneSearchProperties();properties.setEnabled(false);
        workflow=new SqlMetadataDiscoveryWorkflow(new SqlMetadataDiscoveryService(sources,indexes,new LuceneMcpSearchService(properties)));
        gateway=new SqlStudioMetadataGateway(null,null){
            @Override public Result invokeScoped(KernelDataScope scope,String service,Map<String,Object> arguments){
                if(!authorized || !"tenant".equals(scope.tenantId()) || !"owner".equals(scope.userId()) || !"service".equals(service))
                    throw new SecurityException("Fixture authenticated metadata grant denied");
                return new Result(service,workflow.execute(new SqlMetadataDiscoveryWorkflow.Request(arguments,id->authorized&&id.equals("asset")),scope));
            }
        };
        metadata=new SqlStudioMetadataService(null,gateway,jdbc,json);
    }
    private MetadataIndex snapshot(){
        try(var connection=database.getConnection()){
            var meta=connection.getMetaData();var tables=new ArrayList<TableLocation>();
            var columns=new LinkedHashMap<String,List<MetadataColumn>>();var keys=new LinkedHashMap<String,List<MetadataForeignKey>>();
            for(String schema:List.of("APP","OTHER"))try(var rows=meta.getTables(null,schema,"%",new String[]{"TABLE"})){
                while(rows.next()){
                    String name=rows.getString("TABLE_NAME");tables.add(new TableLocation("asset",schema,schema,name,"TABLE",null,0));
                    var fields=new ArrayList<MetadataColumn>();
                    try(var fieldsResult=meta.getColumns(null,schema,name,"%")){
                        while(fieldsResult.next())fields.add(new MetadataColumn("asset",schema,schema,name,fieldsResult.getString("COLUMN_NAME"),
                            fieldsResult.getString("TYPE_NAME"),fieldsResult.getString("TYPE_NAME"),null,fieldsResult.getString("REMARKS"),
                            fieldsResult.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls,fieldsResult.getInt("ORDINAL_POSITION")));
                    }
                    columns.put((schema+"."+name).toLowerCase(Locale.ROOT),fields);
                    var links=new ArrayList<MetadataForeignKey>();
                    try(var imported=meta.getImportedKeys(null,schema,name)){
                        while(imported.next())links.add(new MetadataForeignKey(imported.getString("FK_NAME"),imported.getString("FKCOLUMN_NAME"),
                            imported.getString("PKTABLE_SCHEM"),imported.getString("PKTABLE_SCHEM"),imported.getString("PKTABLE_NAME"),
                            imported.getString("PKCOLUMN_NAME"),imported.getInt("KEY_SEQ")));
                    }
                    keys.put(MetadataIndex.relationshipKey(schema,schema,name),links);
                }
            }
            return new MetadataIndex("asset","oracle",tables,Map.of(),Map.of(),columns,List.of("APP","OTHER"),System.currentTimeMillis(),false,null,null,null,List.of(),keys);
        }catch(SQLException error){throw new IllegalStateException(error);}
    }
}
