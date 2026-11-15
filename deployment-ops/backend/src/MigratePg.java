import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit one-time migration. Never used by the Web startup path. */
public class MigratePg {
 public static void main(String[] args) throws Exception {
  String source=System.getenv("LEGACY_DB_URL");
  if(source==null||!source.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("LEGACY_DB_URL required");
  try(Connection from=DriverManager.getConnection(source,"deployment_ops",System.getenv("DB_PASSWORD"));Connection to=Main.db()){
   for(String statement:Files.readString(Path.of(Main.env("APP_HOME","/app"),"schema.sql")).split(";"))if(!statement.isBlank())try(Statement q=to.createStatement()){q.execute(statement);}
   List<String> tables=List.of("users","nodes","entries","audit");
   for(String table:List.of("users","sessions","nodes","entries","audit"))try(Statement q=to.createStatement();ResultSet r=q.executeQuery("SELECT count(*) FROM "+table)){r.next();if(r.getLong(1)!=0)throw new IllegalStateException("Target database must be empty: "+table);}
   from.setAutoCommit(false);from.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);from.setReadOnly(true);to.setAutoCommit(false);
   Map<String,Long> counts=new LinkedHashMap<>();
   try{
    for(String table:tables){long count=0;try(Statement query=from.createStatement();ResultSet rows=query.executeQuery("SELECT * FROM "+table+" ORDER BY "+(table.equals("users")?"username":"id"))){
     ResultSetMetaData meta=rows.getMetaData();List<String> columns=new ArrayList<>();for(int i=1;i<=meta.getColumnCount();i++)columns.add(meta.getColumnName(i));
     String queryText="INSERT INTO "+table+"("+String.join(",",columns)+") VALUES("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";
     try(PreparedStatement insert=to.prepareStatement(queryText)){
      while(rows.next()){for(int i=1;i<=columns.size();i++){Object value=rows.getObject(i);if(value instanceof Timestamp t)value=t.toInstant().atOffset(ZoneOffset.UTC);insert.setObject(i,value);}insert.executeUpdate();count++;}
     }
    }try(Statement query=to.createStatement();ResultSet check=query.executeQuery("SELECT count(*) FROM "+table)){check.next();if(check.getLong(1)!=count)throw new IllegalStateException("Count mismatch: "+table);}counts.put(table,count);}
    to.commit();
   }catch(Exception failure){to.rollback();throw failure;}
   for(String table:List.of("nodes","entries","audit")){long next;try(Statement q=to.createStatement();ResultSet r=q.executeQuery("SELECT COALESCE(MAX(id),0)+1 FROM "+table)){r.next();next=r.getLong(1);}try(Statement q=to.createStatement()){q.execute("ALTER TABLE "+table+" ALTER COLUMN id RESTART WITH "+next);}}
   try(Statement q=to.createStatement()){q.execute("SHUTDOWN");}
   from.rollback();System.out.println("Migration verified: "+counts+". Sessions intentionally invalidated. Legacy database not modified.");
  }
 }
}
