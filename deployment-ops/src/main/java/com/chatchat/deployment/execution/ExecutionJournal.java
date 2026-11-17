package com.chatchat.deployment.execution;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;
import com.chatchat.deployment.storage.StorageGuard;

/** Indexed embedded H2 execution state; multi-record transitions commit together. */
public final class ExecutionJournal implements AutoCloseable {
    public record Entry(String namespace,String key,JsonNode value){}
    private final Connection connection;
    private final byte[] signingKey;
    public ExecutionJournal(Path root,String password)throws Exception{
        StorageGuard.verifyRuntime();Files.createDirectories(root);signingKey=password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        connection=DriverManager.getConnection("jdbc:h2:file:"+root.toAbsolutePath().resolve("execution")+";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0","deployment_ops",password);
        try(Statement statement=connection.createStatement()){
            statement.execute("CREATE TABLE IF NOT EXISTS execution_records(namespace VARCHAR(128) NOT NULL, record_key VARCHAR(256) NOT NULL, record_json CLOB NOT NULL, updated DOUBLE PRECISION NOT NULL, PRIMARY KEY(namespace,record_key))");
            statement.execute("CREATE INDEX IF NOT EXISTS execution_record_time ON execution_records(namespace,updated DESC)");
        }
        try{Files.setPosixFilePermissions(root.resolve("execution.mv.db"),PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
    }
    public String fingerprint(JsonNode value)throws Exception{var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(signingKey,"HmacSHA256"));return java.util.HexFormat.of().formatHex(mac.doFinal(StateFiles.JSON.writeValueAsBytes(ExecutionJson.canonical(value))));}
    public synchronized JsonNode read(String namespace,String key)throws Exception{
        StorageGuard.verifyRuntime();try(PreparedStatement query=connection.prepareStatement("SELECT record_json FROM execution_records WHERE namespace=? AND record_key=?")){query.setString(1,namespace);query.setString(2,key);try(ResultSet rows=query.executeQuery()){return rows.next()?StateFiles.JSON.readTree(rows.getString(1)):null;}}
    }
    public synchronized List<JsonNode> list(String namespace,int limit)throws Exception{
        StorageGuard.verifyRuntime();List<JsonNode> result=new ArrayList<>();try(PreparedStatement query=connection.prepareStatement("SELECT record_json FROM execution_records WHERE namespace=? ORDER BY updated DESC,record_key LIMIT ?")){query.setString(1,namespace);query.setInt(2,limit);try(ResultSet rows=query.executeQuery()){while(rows.next())result.add(StateFiles.JSON.readTree(rows.getString(1)));}}return result;
    }
    public synchronized void write(String namespace,String key,JsonNode value)throws Exception{write(List.of(new Entry(namespace,key,value)));}
    public synchronized void write(List<Entry> entries)throws Exception{
        StorageGuard.verifyRuntime();connection.setAutoCommit(false);
        try(PreparedStatement update=connection.prepareStatement("MERGE INTO execution_records(namespace,record_key,record_json,updated) KEY(namespace,record_key) VALUES(?,?,?,?)")){
            for(Entry entry:entries){update.setString(1,entry.namespace);update.setString(2,entry.key);update.setString(3,StateFiles.JSON.writeValueAsString(entry.value));update.setDouble(4,StateFiles.now());update.addBatch();}update.executeBatch();connection.commit();
        }catch(Exception error){connection.rollback();throw error;}finally{connection.setAutoCommit(true);}
    }
    public synchronized void expire(String namespace,double olderThan)throws Exception{StorageGuard.verifyRuntime();try(PreparedStatement query=connection.prepareStatement("DELETE FROM execution_records WHERE namespace=? AND updated<?")){query.setString(1,namespace);query.setDouble(2,olderThan);query.executeUpdate();}}
    public synchronized void close()throws SQLException{connection.close();}
}
