import java.util.*;
/** Open only a read-only H2 backup, never the live database. */
public class VerifyBackup {
 public static void main(String[] args)throws Exception{
  String url=System.getenv("DB_URL");
  if(url==null||!url.startsWith("jdbc:h2:file:")||!url.contains("ACCESS_MODE_DATA=r")||!url.contains("IFEXISTS=TRUE"))throw new IllegalArgumentException("Read-only existing backup required");
  Map<String,Object> counts=new LinkedHashMap<>();
  for(String table:List.of("users","nodes","entries","audit"))counts.put(table,Main.sql("SELECT count(*) AS n FROM "+table).get(0).get("n"));
  if(((Number)counts.get("users")).longValue()<1)throw new IllegalStateException("No users in backup");
  System.out.println("Verified independent H2 backup: "+counts);
 }
}
