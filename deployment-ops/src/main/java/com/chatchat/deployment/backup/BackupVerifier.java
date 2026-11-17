package com.chatchat.deployment.backup;
import java.nio.file.*;
import java.sql.*;
public final class BackupVerifier {
    private BackupVerifier(){}
    public static void prepare(Path directory)throws Exception{
        verify(directory);
        String url="jdbc:h2:file:"+directory.toRealPath().resolve("deployment-ops")+";IFEXISTS=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        try(var connection=DriverManager.getConnection(url,"deployment_ops",System.getProperty("OPS_DB_PASSWORD",System.getenv("OPS_DB_PASSWORD")));var statement=connection.createStatement()){
            statement.executeUpdate("DELETE FROM sessions");
            statement.executeUpdate("INSERT INTO audit(actor,action,target,outcome) VALUES('system','platform-restore','H2 management database','verified; sessions invalidated')");
        }
    }
    public static void verify(Path directory) throws Exception {
        Path real=directory.toRealPath();if(!real.startsWith(Path.of("/mnt/disk01")))throw new IllegalArgumentException("Restore verification must remain on required disk");
        String url="jdbc:h2:file:"+real.resolve("deployment-ops")+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        try(var connection=DriverManager.getConnection(url,"deployment_ops",System.getProperty("OPS_DB_PASSWORD",System.getenv("OPS_DB_PASSWORD")));var statement=connection.createStatement()){
            var version=statement.executeQuery("SELECT MAX(version) FROM schema_version");version.next();if(version.getInt(1)>2)throw new IllegalArgumentException("Backup requires a newer application version");
            var users=statement.executeQuery("SELECT COUNT(*) FROM users WHERE role='admin'");users.next();if(users.getInt(1)<1)throw new IllegalArgumentException("Backup has no administrator");
            statement.executeQuery("SELECT COUNT(*) FROM nodes").close();statement.executeQuery("SELECT COUNT(*) FROM audit").close();
        }
        System.out.println("H2 backup verified");
    }
}
