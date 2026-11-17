import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;import java.net.http.*;import java.nio.file.*;import java.io.*;import java.sql.*;import java.util.*;import java.util.concurrent.*;import java.security.*;import javax.crypto.*;import javax.crypto.spec.*;import java.time.*;
public class Main {
 static final ObjectMapper J=new ObjectMapper(); static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 static volatile javax.sql.DataSource nativeDataSource;
 static volatile Object nativeRoutes;
 public static void registerNativeRoutes(Object routes){nativeRoutes=routes;}
 public static void initializeNative(javax.sql.DataSource source,String schema,String password,String token,String executorUrl)throws Exception{
  nativeDataSource=source;
  sql("CREATE TABLE IF NOT EXISTS schema_version(version int PRIMARY KEY, installed_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP)");
  var versions=sql("SELECT MAX(version) AS version FROM schema_version");
  if(versions.get(0).get("version") instanceof Number v && v.intValue()>2)throw new IllegalStateException("Database schema is newer than this application; restore compatible version");
  for(String statement:schema.split(";"))if(!statement.isBlank())sql(statement);
  if(sql("SELECT username FROM users").isEmpty() && password!=null){
   if(password==null||password.length()<6)throw new IllegalArgumentException("Set ADMIN_PASSWORD (6+ characters) for first installation");
   String salt=random();sql("INSERT INTO users VALUES(?,?,?,?)","admin",salt,hash(password,salt),"admin");
  }
  if(token!=null&&!token.isBlank()&&sql("SELECT id FROM nodes").isEmpty())sql("INSERT INTO nodes(name,url,token) VALUES(?,?,?)","local",executorUrl,token);
  sql("MERGE INTO schema_version(version) KEY(version) VALUES(2)");
 }
 static synchronized void initializeAdministrator(HttpExchange x)throws Exception{
  if(!sql("SELECT username FROM users").isEmpty()){send(x,409,Map.of("error","Already initialized"));return;}
  var b=body(x);String expected=System.getProperty("OPS_SETUP_TOKEN",System.getenv().getOrDefault("OPS_SETUP_TOKEN",""));
  if(expected.isBlank()||!"1".equals(x.getRequestHeaders().getFirst("X-Ops-Request"))||!MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),String.valueOf(b.get("token")).getBytes(java.nio.charset.StandardCharsets.UTF_8))){send(x,403,Map.of("error","Local installation token required"));return;}
  String password=String.valueOf(b.getOrDefault("password",""));if(password.length()<12||password.length()>128)throw new IllegalArgumentException("Administrator password must contain 12 to 128 characters");
  String salt=random();sql("INSERT INTO users VALUES(?,?,?,?)","admin",salt,hash(password,salt),"admin");audit("admin","initialize","platform","success");send(x,200,Map.of("initialized",true));
 }
 public static void handleNative(HttpExchange exchange)throws Exception{api(exchange);}
 static String env(String n,String d){return System.getenv().getOrDefault(n,d);} static Connection db() throws Exception{if(nativeDataSource!=null)return nativeDataSource.getConnection();return DriverManager.getConnection(env("DB_URL","jdbc:h2:file:/data/deployment-ops;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000;WRITE_DELAY=0"),"deployment_ops",env("DB_PASSWORD",""));}
 static List<Map<String,Object>> sql(String s,Object...args)throws Exception{try(var c=db();var q=c.prepareStatement(s)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);if(!q.execute())return List.of();try(var r=q.getResultSet()){var out=new ArrayList<Map<String,Object>>();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.put(r.getMetaData().getColumnLabel(i),jsonValue(r.getObject(i)));out.add(row);}return out;}}}
 static Object jsonValue(Object value){if(value instanceof java.time.OffsetDateTime t)return t.toInstant().toString();if(value instanceof java.sql.Timestamp t)return t.toInstant().toString();return value;}
 static String hash(String pass,String salt)throws Exception{return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pass.toCharArray(),salt.getBytes(),210000,256)).getEncoded());}
 static String random(){return UUID.randomUUID().toString()+UUID.randomUUID();}
 static Map<String,Object> body(HttpExchange x)throws Exception{byte[] b=x.getRequestBody().readNBytes(1024*1024+1);if(b.length>1024*1024)throw new IllegalArgumentException("request too large");return J.readValue(b,Map.class);}
 static void send(HttpExchange x,int status,Object v)throws Exception{byte[] b=J.writeValueAsBytes(v);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,b.length);x.getResponseBody().write(b);x.close();}
 static Object agent(Map<String,Object> node,String path,String method,byte[] data,String digest)throws Exception{var req=HttpRequest.newBuilder(URI.create(node.get("url")+path)).timeout(Duration.ofSeconds(path.equals("/inventory")?5:path.contains("import")?600:(path.equals("/releases")||path.equals("/rollback")?240:100))).header("Authorization","Bearer "+node.get("token"));if(digest!=null)req.header("X-SHA256",digest);req.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(data));var r=HTTP.send(req.build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()!=200)throw new IllegalArgumentException(r.body());return J.readValue(r.body(),Object.class);}
 static Object upload(Map<String,Object> node,HttpExchange x,String actor,String role)throws Exception{
  long length=Long.parseLong(x.getRequestHeaders().getFirst("Content-Length"));if(length<1||length>256*1024*1024)throw new IllegalArgumentException("web import limit 256 MiB");
  var publisher=HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(()->x.getRequestBody()),length);
  var request=HttpRequest.newBuilder(URI.create(node.get("url")+"/images/import")).timeout(Duration.ofMinutes(10)).header("Authorization","Bearer "+node.get("token")).header("X-SHA256",x.getRequestHeaders().getFirst("X-SHA256")).header("X-Ops-Actor",actor).header("X-Ops-Role",role).POST(publisher).build();
  var response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IllegalArgumentException(response.body());return J.readValue(response.body(),Object.class);
 }
 static synchronized void cacheInstallation(long node,Map<String,Object> job)throws Exception{
  String id=job.get("id").toString(),status=job.get("status").toString();var old=sql("SELECT status FROM installation_cache WHERE node_id=? AND job_id=?",node,id);
  sql("MERGE INTO installation_cache(node_id,job_id,status,public_json,updated_at) KEY(node_id,job_id) VALUES(?,?,?,?,CURRENT_TIMESTAMP)",node,id,status,J.writeValueAsString(job));
  if((old.isEmpty()||!status.equals(old.get(0).get("status")))&&List.of("succeeded","failed","rolled_back","recovery_required","cancelled","interrupted","stopped").contains(status))audit(String.valueOf(job.getOrDefault("actor","executor")),"installation:"+status,"node/"+node+"/"+id,status);
 }
 static Object packageProxy(Map<String,Object> node,String path,String method,HttpExchange x,String actor,String role)throws Exception{
  var request=HttpRequest.newBuilder(URI.create(node.get("url")+path)).timeout(method.equals("GET")?Duration.ofSeconds(15):Duration.ofMinutes(3)).header("Authorization","Bearer "+node.get("token")).header("X-Ops-Actor",actor).header("X-Ops-Role",role);
  if(method.equals("POST")){
   if(path.matches("/packages/uploads/[a-f0-9]{32}/[0-9]+")){
    long length=Long.parseLong(x.getRequestHeaders().getFirst("Content-Length"));if(length<1||length>4*1024*1024)throw new IllegalArgumentException("Chunk limit is 4 MiB");
    request.POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(()->x.getRequestBody()),length));
   }else request.POST(HttpRequest.BodyPublishers.ofByteArray(J.writeValueAsBytes(body(x))));
  }else request.GET();
  var response=HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IllegalArgumentException(response.body());Object value=J.readValue(response.body(),Object.class);
  if(path.equals("/installations")&&value instanceof List<?> jobs)for(Object job:jobs)cacheInstallation(((Number)node.get("id")).longValue(),(Map<String,Object>)job);
  else if(path.startsWith("/installations/")&&!path.endsWith("preflight")&&value instanceof Map<?,?> job&&job.containsKey("id"))cacheInstallation(((Number)node.get("id")).longValue(),(Map<String,Object>)job);
  return value;
 }
 static void audit(String user,String action,String target,String outcome)throws Exception{sql("INSERT INTO audit(actor,action,target,outcome) VALUES(?,?,?,?)",user,action,target,outcome);}
 static void api(HttpExchange x)throws Exception{
  String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
  if(path.equals("/api/health")){sql("SELECT 1");send(x,200,Map.of("status","UP","database","H2 embedded"));return;}
  if(path.equals("/api/setup")&&method.equals("GET")){send(x,200,Map.of("required",sql("SELECT username FROM users").isEmpty()));return;}
  if(path.equals("/api/setup")&&method.equals("POST")){initializeAdministrator(x);return;}
  if(path.equals("/api/login")&&method.equals("POST")){
   String peer=x.getRemoteAddress().getAddress().getHostAddress();var recent=sql("SELECT count(*) AS n FROM audit WHERE actor=? AND action='login-failed' AND created_at>?",peer,java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(5));if(((Number)recent.get(0).get("n")).intValue()>=10){send(x,429,Map.of("error","too many attempts"));return;}
   var b=body(x);var users=sql("SELECT * FROM users WHERE username=?",b.get("username"));
   if(users.isEmpty()||!MessageDigest.isEqual(hash(String.valueOf(b.get("password")),users.get(0).get("salt").toString()).getBytes(),users.get(0).get("password_hash").toString().getBytes())){audit(peer,"login-failed","login","denied");send(x,401,Map.of("error","invalid credentials"));return;}
   String token=random();sql("INSERT INTO sessions(token,username,expires) VALUES(?,?,?)",hash(token,"session"),b.get("username"),java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusHours(8));x.getResponseHeaders().add("Set-Cookie","ops_session="+token+"; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800");audit(b.get("username").toString(),"login","session","success");send(x,200,Map.of("role",users.get(0).get("role")));return;
  }
  String cookie=x.getRequestHeaders().getFirst("Cookie"),token="";if(cookie!=null)for(String c:cookie.split(";"))if(c.trim().startsWith("ops_session="))token=c.trim().substring(12);
  var user=sql("SELECT u.username,u.role FROM sessions s JOIN users u USING(username) WHERE s.token=? AND expires>now()",hash(token,"session"));if(user.isEmpty()){send(x,401,Map.of("error","login required"));return;}
  if(path.equals("/api/me")&&method.equals("GET")){send(x,200,user.get(0));return;}
  String actor=user.get(0).get("username").toString();boolean write=!method.equals("GET");
  if(write){if(!path.equals("/api/logout")&&!"admin".equals(user.get(0).get("role"))){send(x,403,Map.of("error","admin required"));return;}if(!"1".equals(x.getRequestHeaders().getFirst("X-Ops-Request"))){send(x,403,Map.of("error","request header required"));return;}}
  if(ManagerApi.route(x,actor,user.get(0).get("role").toString()))return;
  if(nativeRoutes!=null){try{if(Boolean.TRUE.equals(nativeRoutes.getClass().getMethod("route",HttpExchange.class,String.class,String.class).invoke(nativeRoutes,x,actor,user.get(0).get("role").toString())))return;}catch(java.lang.reflect.InvocationTargetException error){if(error.getCause() instanceof Exception cause)throw cause;throw error;}}
  if(path.equals("/api/logout")&&write){sql("DELETE FROM sessions WHERE token=?",hash(token,"session"));send(x,200,Map.of("ok",true));return;}
  if(path.equals("/api/nodes")){
   if(write){var b=body(x);URI uri=URI.create(b.get("url").toString());if(!List.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||!List.of("", "/").contains(uri.getPath()))throw new IllegalArgumentException("executor base URL required");if(uri.getScheme().equals("http")&&!List.of("executor","localhost","127.0.0.1","[::1]").contains(uri.getHost()))throw new IllegalArgumentException("Remote node HTTPS is required");sql("INSERT INTO nodes(name,url,token) VALUES(?,?,?)",b.get("name"),b.get("url").toString().replaceAll("/$",""),b.get("token"));audit(actor,"node-register",b.get("name").toString(),"success");}
   send(x,200,sql("SELECT id,name,url FROM nodes ORDER BY id"));return;
  }
  if(path.equals("/api/inventory")){
   var result=new ArrayList<Object>();for(var n:sql("SELECT * FROM nodes ORDER BY id")){var entry=new LinkedHashMap<String,Object>();entry.put("id",n.get("id"));entry.put("name",n.get("name"));try{entry.put("data",agent(n,"/inventory","GET",null,null));var last=sql("SELECT outcome FROM audit WHERE action='node-state' AND target=? ORDER BY id DESC LIMIT 1",n.get("name").toString());if(!last.isEmpty()&&"offline".equals(last.get(0).get("outcome")))audit("system","node-state",n.get("name").toString(),"online");sql("UPDATE nodes SET last_seen=now() WHERE id=?",n.get("id"));}catch(Exception e){entry.put("error",e.getMessage());var last=sql("SELECT outcome FROM audit WHERE action='node-state' AND target=? ORDER BY id DESC LIMIT 1",n.get("name").toString());if(last.isEmpty()||!"offline".equals(last.get(0).get("outcome")))audit("system","node-state",n.get("name").toString(),"offline");}result.add(entry);}send(x,200,result);return;
  }
  if(path.equals("/api/entries")){
   if(write){var b=body(x);String url=b.get("url").toString();if(!url.matches("https?://[^\\s]+"))throw new IllegalArgumentException("HTTP URL required");sql("INSERT INTO entries(name,url,project) VALUES(?,?,?)",b.get("name"),url,b.getOrDefault("project",""));audit(actor,"entry-register",url,"success");}var links=new ArrayList<Map<String,Object>>(sql("SELECT * FROM entries ORDER BY id"));
   for(var node:sql("SELECT * FROM nodes ORDER BY id"))try{packageProxy(node,"/installations","GET",x,actor,user.get(0).get("role").toString());}catch(Exception unavailable){}
   for(var cache:sql("SELECT node_id,public_json FROM installation_cache")){
    var job=J.readValue(cache.get("public_json").toString(),Map.class);
    if(Boolean.TRUE.equals(job.get("active"))&&"succeeded".equals(job.get("status"))&&job.get("links") instanceof List<?> installedLinks){
     int index=0;for(Object raw:installedLinks){var link=(Map<?,?>)raw;var item=new LinkedHashMap<String,Object>();item.put("id","installation-"+cache.get("node_id")+"-"+job.get("id")+"-"+(index++));item.put("name",link.get("name"));item.put("url",link.get("url"));item.put("project",job.get("environment"));links.add(item);}
    }
   }send(x,200,links);return;
  }
  if(path.equals("/api/users")){
   if(!"admin".equals(user.get(0).get("role"))){send(x,403,Map.of("error","admin required"));return;}
   if(write){var b=body(x);String name=String.valueOf(b.get("username")),pass=String.valueOf(b.get("password")),role=String.valueOf(b.get("role"));if(!name.matches("[A-Za-z0-9_-]{3,40}")||pass.length()<8||!List.of("admin","viewer").contains(role))throw new IllegalArgumentException("username, password (8+ chars), role admin/viewer required");if(name.equals(actor)&&!role.equals("admin"))throw new IllegalArgumentException("cannot remove own administrator role");String salt=random();sql("MERGE INTO users(username,salt,password_hash,role) KEY(username) VALUES(?,?,?,?)",name,salt,hash(pass,salt),role);sql("DELETE FROM sessions WHERE username=?",name);audit(actor,"user-update",name,"success");}
   send(x,200,sql("SELECT username,role FROM users ORDER BY username"));return;
  }
  if(path.equals("/api/installations")&&method.equals("GET")){
   var result=new ArrayList<Object>();for(var node:sql("SELECT * FROM nodes ORDER BY id")){
    var item=new LinkedHashMap<String,Object>();item.put("node",node.get("id"));item.put("name",node.get("name"));
    try{item.put("jobs",packageProxy(node,"/installations","GET",x,actor,user.get(0).get("role").toString()));item.put("online",true);}catch(Exception error){var jobs=new ArrayList<Object>();for(var cache:sql("SELECT public_json FROM installation_cache WHERE node_id=? ORDER BY updated_at DESC",node.get("id")))jobs.add(J.readValue(cache.get("public_json").toString(),Object.class));item.put("jobs",jobs);item.put("online",false);item.put("message","Node unavailable; showing last known installation records");}result.add(item);
   }send(x,200,result);return;
  }
  if(path.matches("/api/nodes/[0-9]+/(packages(?:/[a-f0-9]{64}/retire|/uploads(?:/[a-f0-9]{32}(?:/[0-9]+|/commit)?)?)?|installations(?:/preflight|/[a-f0-9]{32}(?:/(resume|cancel|rollback|stop|start|health))?)?)")){
   String[] parts=path.split("/",5);var found=sql("SELECT * FROM nodes WHERE id=?",Long.parseLong(parts[3]));if(found.isEmpty())throw new IllegalArgumentException("Node missing");
   if(!List.of("GET","POST").contains(method))throw new IllegalArgumentException("Method not supported");
   if(write)audit(actor,"requested:"+method,path,"pending");
   try{Object value=packageProxy(found.get(0),"/"+parts[4],method,x,actor,user.get(0).get("role").toString());if(write)audit(actor,method,path,"accepted");send(x,200,value);}catch(Exception error){if(write)audit(actor,method,path,"failed");throw error;}return;
  }
  if(path.equals("/api/audit")){send(x,200,sql("SELECT * FROM audit ORDER BY id DESC LIMIT 500"));return;}
  if(path.matches("/api/nodes/[0-9]+/(containers/[a-f0-9]{12,64}/(logs|stats|inspect|start|stop|restart)|images/import|releases|rollback|registry)")){
   String[] parts=path.split("/",5);var nodes=sql("SELECT * FROM nodes WHERE id=?",Long.parseLong(parts[3]));if(nodes.isEmpty())throw new IllegalArgumentException("node missing");String op="/"+parts[4];boolean release=op.equals("/releases")||op.equals("/rollback");boolean mut=op.matches(".*(start|stop|restart|import)$");if(!release&&mut!=write)throw new IllegalArgumentException("invalid method");
   if(nativeDataSource!=null&&((release&&write)||(mut&&!op.endsWith("import"))))throw new IllegalArgumentException("Use approved resource plans or the signed application workflow");
   audit(actor,"requested:"+method,path,"pending");try{byte[] bytes=null;if(release&&write)bytes=J.writeValueAsBytes(body(x));Object result=op.endsWith("import")?upload(nodes.get(0),x,actor,user.get(0).get("role").toString()):op.endsWith("/inspect")?ManagerApi.call(nodes.get(0),op,method,bytes,actor,user.get(0).get("role").toString()):agent(nodes.get(0),op,method,bytes,null);audit(actor,method,path,"success");send(x,200,result);}catch(Exception e){audit(actor,method,path,"failed: "+e.getMessage());throw e;}return;
  }
  send(x,404,Map.of("error","route not found"));
 }
 public static void main(String[] args)throws Exception{
  if(!env("DB_URL","jdbc:h2:file:/data/deployment-ops").startsWith("jdbc:h2:file:"))throw new IllegalArgumentException("Embedded file database required");
  for(String statement:Files.readString(Path.of(env("APP_HOME","/app"),"schema.sql")).split(";"))if(!statement.isBlank())sql(statement);
  if(sql("SELECT username FROM users").isEmpty()){String salt=random();sql("INSERT INTO users VALUES(?,?,?,?)","admin",salt,hash(System.getenv("ADMIN_PASSWORD"),salt),"admin");}
  if(sql("SELECT id FROM nodes").isEmpty())sql("INSERT INTO nodes(name,url,token) VALUES(?,?,?)","local","http://executor:8091",System.getenv("EXECUTOR_TOKEN"));
  var server=HttpServer.create(new InetSocketAddress(Integer.parseInt(env("OPS_HTTP_PORT","8080"))),64);server.setExecutor(Executors.newFixedThreadPool(24));server.createContext("/",x->{try{if(x.getRequestURI().getPath().startsWith("/api/"))api(x);else{String path=x.getRequestURI().getPath();Path root=Path.of(env("APP_HOME","/app"),"web");Path file=root.resolve(path.equals("/")?(Files.isRegularFile(root.resolve("manager.html"))?"manager.html":"index.html"):path.substring(1)).normalize();if(!file.startsWith(root)||!Files.isRegularFile(file)){send(x,404,Map.of("error","not found"));return;}x.getResponseHeaders().set("Content-Type",path.endsWith(".js")?"text/javascript; charset=utf-8":path.endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8");x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'");x.getResponseHeaders().set("Cache-Control","no-cache");byte[] b=Files.readAllBytes(file);x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();}}catch(Exception e){try{send(x,400,Map.of("error",e.getMessage()==null?"request failed":e.getMessage()));}catch(Exception ignored){x.close();}}});Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(2);try{sql("SHUTDOWN");}catch(Exception ignored){}}));server.start();
 }
}
