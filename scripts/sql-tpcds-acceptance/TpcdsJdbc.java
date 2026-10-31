import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class TpcdsJdbc {
 static String text(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";}
 public static void main(String[] args)throws Exception{
  Properties p=new Properties();try(var in=Files.newBufferedReader(Path.of(args[0]))){p.load(in);}
  String url=decoded(p,"url"),user=decoded(p,"user"),password=decoded(p,"password");
  Class.forName("com.mysql.cj.jdbc.Driver");
  String sql=Files.readString(Path.of(args[1]));
  if(!sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select"))throw new IllegalArgumentException("Baseline accepts SELECT only");
  long started=System.nanoTime();
  try(var c=DriverManager.getConnection(url,user,password)){
   c.setReadOnly(true);try(var s=c.createStatement()){
    s.setQueryTimeout(10);s.setMaxRows(100);try(var r=s.executeQuery(sql)){
     var m=r.getMetaData();var rows=new ArrayList<String>();
     while(r.next()){var fields=new ArrayList<String>();for(int i=1;i<=m.getColumnCount();i++)fields.add(text(m.getColumnLabel(i))+":"+(r.getObject(i)==null?"null":text(r.getString(i))));rows.add("{"+String.join(",",fields)+"}");}
     System.out.println("{\"source\":\"DIRECT_MYSQL_JDBC\",\"readOnly\":true,\"durationMs\":"+((System.nanoTime()-started)/1000000)+",\"rows\":["+String.join(",",rows)+"]}");
    }
   }
  }
 }
 static String decoded(Properties p,String key){return new String(Base64.getDecoder().decode(p.getProperty(key)),StandardCharsets.UTF_8);}
}
