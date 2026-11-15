import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;import java.net.http.*;import java.nio.file.*;import java.io.*;import java.sql.*;import java.util.*;import java.util.concurrent.*;import java.security.*;import javax.crypto.*;import javax.crypto.spec.*;import java.time.*;
public class Main {
 static final ObjectMapper J=new ObjectMapper(); static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 static String env(String n,String d){return System.getenv().getOrDefault(n,d);} static Connection db() throws Exception{return DriverManager.getConnection(env("DB_URL","jdbc:postgresql://db:5432/deployment_ops"),"deployment_ops",System.getenv("DB_PASSWORD"));}
 static List<Map<String,Object>> sql(String s,Object...args)throws Exception{try(var c=db();var q=c.prepareStatement(s)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);if(!q.execute())return List.of();try(var r=q.getResultSet()){var out=new ArrayList<Map<String,Object>>();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.put(r.getMetaData().getColumnLabel(i),r.getObject(i));out.add(row);}return out;}}}
 static String hash(String pass,String salt)throws Exception{return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pass.toCharArray(),salt.getBytes(),210000,256)).getEncoded());}
 static String random(){return UUID.randomUUID().toString()+UUID.randomUUID();}
 static Map<String,Object> body(HttpExchange x)throws Exception{byte[] b=x.getRequestBody().readNBytes(1024*1024+1);if(b.length>1024*1024)throw new IllegalArgumentException("request too large");return J.readValue(b,Map.class);}
 static void send(HttpExchange x,int status,Object v)throws Exception{byte[] b=J.writeValueAsBytes(v);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,b.length);x.getResponseBody().write(b);x.close();}
 static Object agent(Map<String,Object> node,String path,String method,byte[] data,String digest)throws Exception{var req=HttpRequest.newBuilder(URI.create(node.get("url")+path)).timeout(Duration.ofSeconds(path.contains("import")?600:(path.equals("/releases")||path.equals("/rollback")?240:100))).header("Authorization","Bearer "+node.get("token"));if(digest!=null)req.header("X-SHA256",digest);req.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(data));var r=HTTP.send(req.build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()!=200)throw new IllegalArgumentException(r.body());return J.readValue(r.body(),Object.class);}
 static Object upload(Map<String,Object> node,HttpExchange x)throws Exception{
  long length=Long.parseLong(x.getRequestHeaders().getFirst("Content-Length"));if(length<1||length>256*1024*1024)throw new IllegalArgumentException("web import limit 256 MiB");
  var publisher=HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(()->x.getRequestBody()),length);
  var request=HttpRequest.newBuilder(URI.create(node.get("url")+"/images/import")).timeout(Duration.ofMinutes(10)).header("Authorization","Bearer "+node.get("token")).header("X-SHA256",x.getRequestHeaders().getFirst("X-SHA256")).POST(publisher).build();
  var response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IllegalArgumentException(response.body());return J.readValue(response.body(),Object.class);
 }
 static void audit(String user,String action,String target,String outcome)throws Exception{sql("INSERT INTO audit(actor,action,target,outcome) VALUES(?,?,?,?)",user,action,target,outcome);}
 static void api(HttpExchange x)throws Exception{
  String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
  if(path.equals("/api/health")){sql("SELECT 1");send(x,200,Map.of("status","UP"));return;}
  if(path.equals("/api/login")&&method.equals("POST")){
   String peer=x.getRemoteAddress().getAddress().getHostAddress();var recent=sql("SELECT count(*) AS n FROM audit WHERE actor=? AND action='login-failed' AND created_at>now()-interval '5 minutes'",peer);if(((Number)recent.get(0).get("n")).intValue()>=10){send(x,429,Map.of("error","too many attempts"));return;}
   var b=body(x);var users=sql("SELECT * FROM users WHERE username=?",b.get("username"));
   if(users.isEmpty()||!MessageDigest.isEqual(hash(String.valueOf(b.get("password")),users.get(0).get("salt").toString()).getBytes(),users.get(0).get("password_hash").toString().getBytes())){audit(peer,"login-failed","login","denied");send(x,401,Map.of("error","invalid credentials"));return;}
   String token=random();sql("INSERT INTO sessions(token,username,expires) VALUES(?,?,now()+interval '8 hours')",hash(token,"session"),b.get("username"));x.getResponseHeaders().add("Set-Cookie","ops_session="+token+"; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800");audit(b.get("username").toString(),"login","session","success");send(x,200,Map.of("role",users.get(0).get("role")));return;
  }
  String cookie=x.getRequestHeaders().getFirst("Cookie"),token="";if(cookie!=null)for(String c:cookie.split(";"))if(c.trim().startsWith("ops_session="))token=c.trim().substring(12);
  var user=sql("SELECT u.username,u.role FROM sessions s JOIN users u USING(username) WHERE s.token=? AND expires>now()",hash(token,"session"));if(user.isEmpty()){send(x,401,Map.of("error","login required"));return;}
  String actor=user.get(0).get("username").toString();boolean write=!method.equals("GET");
  if(write){if(!path.equals("/api/logout")&&!"admin".equals(user.get(0).get("role"))){send(x,403,Map.of("error","admin required"));return;}if(!"1".equals(x.getRequestHeaders().getFirst("X-Ops-Request"))){send(x,403,Map.of("error","request header required"));return;}}
  if(path.equals("/api/logout")&&write){sql("DELETE FROM sessions WHERE token=?",hash(token,"session"));send(x,200,Map.of("ok",true));return;}
  if(path.equals("/api/nodes")){
   if(write){var b=body(x);URI uri=URI.create(b.get("url").toString());if(!List.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||!List.of("", "/").contains(uri.getPath()))throw new IllegalArgumentException("executor base URL required");sql("INSERT INTO nodes(name,url,token) VALUES(?,?,?)",b.get("name"),b.get("url").toString().replaceAll("/$",""),b.get("token"));audit(actor,"node-register",b.get("name").toString(),"success");}
   send(x,200,sql("SELECT id,name,url FROM nodes ORDER BY id"));return;
  }
  if(path.equals("/api/inventory")){
   var result=new ArrayList<Object>();for(var n:sql("SELECT * FROM nodes ORDER BY id")){var entry=new LinkedHashMap<String,Object>();entry.put("id",n.get("id"));entry.put("name",n.get("name"));try{entry.put("data",agent(n,"/inventory","GET",null,null));var last=sql("SELECT outcome FROM audit WHERE action='node-state' AND target=? ORDER BY id DESC LIMIT 1",n.get("name").toString());if(!last.isEmpty()&&"offline".equals(last.get(0).get("outcome")))audit("system","node-state",n.get("name").toString(),"online");sql("UPDATE nodes SET last_seen=now() WHERE id=?",n.get("id"));}catch(Exception e){entry.put("error",e.getMessage());var last=sql("SELECT outcome FROM audit WHERE action='node-state' AND target=? ORDER BY id DESC LIMIT 1",n.get("name").toString());if(last.isEmpty()||!"offline".equals(last.get(0).get("outcome")))audit("system","node-state",n.get("name").toString(),"offline");}result.add(entry);}send(x,200,result);return;
  }
  if(path.equals("/api/entries")){
   if(write){var b=body(x);String url=b.get("url").toString();if(!url.matches("https?://[^\\s]+"))throw new IllegalArgumentException("HTTP URL required");sql("INSERT INTO entries(name,url,project) VALUES(?,?,?)",b.get("name"),url,b.getOrDefault("project",""));audit(actor,"entry-register",url,"success");}send(x,200,sql("SELECT * FROM entries ORDER BY id"));return;
  }
  if(path.equals("/api/users")){
   if(!"admin".equals(user.get(0).get("role"))){send(x,403,Map.of("error","admin required"));return;}
   if(write){var b=body(x);String name=String.valueOf(b.get("username")),pass=String.valueOf(b.get("password")),role=String.valueOf(b.get("role"));if(!name.matches("[A-Za-z0-9_-]{3,40}")||pass.length()<8||!List.of("admin","viewer").contains(role))throw new IllegalArgumentException("username, password (8+ chars), role admin/viewer required");if(name.equals(actor)&&!role.equals("admin"))throw new IllegalArgumentException("cannot remove own administrator role");String salt=random();sql("INSERT INTO users VALUES(?,?,?,?) ON CONFLICT(username) DO UPDATE SET salt=excluded.salt,password_hash=excluded.password_hash,role=excluded.role",name,salt,hash(pass,salt),role);sql("DELETE FROM sessions WHERE username=?",name);audit(actor,"user-update",name,"success");}
   send(x,200,sql("SELECT username,role FROM users ORDER BY username"));return;
  }
  if(path.equals("/api/audit")){send(x,200,sql("SELECT * FROM audit ORDER BY id DESC LIMIT 500"));return;}
  if(path.matches("/api/nodes/\\d+/(containers/[a-f0-9]{12,64}/(logs|stats|inspect|start|stop|restart)|images/import|releases|rollback|registry)")){
   String[] parts=path.split("/",5);var nodes=sql("SELECT * FROM nodes WHERE id=?",Long.parseLong(parts[3]));if(nodes.isEmpty())throw new IllegalArgumentException("node missing");String op="/"+parts[4];boolean release=op.equals("/releases")||op.equals("/rollback");boolean mut=op.matches(".*(start|stop|restart|import)$");if(!release&&mut!=write)throw new IllegalArgumentException("invalid method");
   audit(actor,"requested:"+method,path,"pending");try{byte[] bytes=null;if(release&&write)bytes=J.writeValueAsBytes(body(x));Object result=op.endsWith("import")?upload(nodes.get(0),x):agent(nodes.get(0),op,method,bytes,null);audit(actor,method,path,"success");send(x,200,result);}catch(Exception e){audit(actor,method,path,"failed: "+e.getMessage());throw e;}return;
  }
  send(x,404,Map.of("error","route not found"));
 }
 public static void main(String[] args)throws Exception{
  for(String statement:Files.readString(Path.of("/app/schema.sql")).split(";"))if(!statement.isBlank())sql(statement);
  if(sql("SELECT username FROM users").isEmpty()){String salt=random();sql("INSERT INTO users VALUES(?,?,?,?)","admin",salt,hash(System.getenv("ADMIN_PASSWORD"),salt),"admin");}
  if(sql("SELECT id FROM nodes").isEmpty())sql("INSERT INTO nodes(name,url,token) VALUES(?,?,?)","local","http://executor:8091",System.getenv("EXECUTOR_TOKEN"));
  var server=HttpServer.create(new InetSocketAddress(8080),64);server.setExecutor(Executors.newFixedThreadPool(24));server.createContext("/",x->{try{if(x.getRequestURI().getPath().startsWith("/api/"))api(x);else{String path=x.getRequestURI().getPath();Path root=Path.of("/app/web");Path file=root.resolve(path.equals("/")?"index.html":path.substring(1)).normalize();if(!file.startsWith(root)||!Files.isRegularFile(file)){send(x,404,Map.of("error","not found"));return;}x.getResponseHeaders().set("Content-Type",path.endsWith(".js")?"text/javascript; charset=utf-8":path.endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8");x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'");byte[] b=Files.readAllBytes(file);x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();}}catch(Exception e){try{send(x,400,Map.of("error",e.getMessage()==null?"request failed":e.getMessage()));}catch(Exception ignored){x.close();}}});server.start();
 }
}
