import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Authenticated resource operations. The Web process never receives a Docker socket. */
public final class ManagerApi {
    static final Semaphore LOG_STREAMS = new Semaphore(4);

    static boolean route(HttpExchange x, String actor, String role) throws Exception {
        String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
        if(!path.matches("/api/nodes/[0-9]+/manager/.*"))return false;
        String[] parts=path.split("/",5);
        var found=Main.sql("SELECT * FROM nodes WHERE id=?",Long.parseLong(parts[3]));
        if(found.isEmpty())throw new IllegalArgumentException("Node not found");
        var node=found.get(0);String op="/"+parts[4];
        if(method.equals("GET")&&op.matches("/manager/logs/[a-f0-9]{64}")){
            if(!LOG_STREAMS.tryAcquire()){Main.send(x,429,Map.of("error","Log stream limit reached"));return true;}
            x.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
            x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Accel-Buffering","no");x.sendResponseHeaders(200,0);
            try{
                for(int i=0;i<15;i++){
                    Object result=call(node,op,"GET",null,actor,role);
                    String event="id: "+System.currentTimeMillis()+"\nevent: logs\ndata: "+Main.J.writeValueAsString(result)+"\n\n";
                    x.getResponseBody().write(event.getBytes(StandardCharsets.UTF_8));x.getResponseBody().flush();Thread.sleep(2000);
                }
            }catch(Exception closed){/* disconnects do not cancel resource operations */}
            finally{x.close();LOG_STREAMS.release();}
            return true;
        }
        if(method.equals("GET")&&op.matches("/manager/export/[a-f0-9]{64}")){
            if(!role.equals("admin")){Main.send(x,403,Map.of("error","Administrator required for image export"));return true;}
            Main.audit(actor,"image-export",path,"requested");
            var request=HttpRequest.newBuilder(URI.create(node.get("url")+op)).timeout(Duration.ofMinutes(10)).header("Authorization","Bearer "+node.get("token")).GET().build();
            var response=Main.HTTP.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()){
                if(response.statusCode()!=200)throw new IllegalArgumentException("Image export rejected by node");
                x.getResponseHeaders().set("Content-Type","application/x-tar");x.getResponseHeaders().set("Content-Disposition","attachment; filename=image-"+op.substring(op.lastIndexOf('/')+1,op.lastIndexOf('/')+13)+".tar");x.sendResponseHeaders(200,0);body.transferTo(x.getResponseBody());
            }finally{x.close();}
            Main.audit(actor,"image-export",path,"success");return true;
        }
        boolean read=method.equals("GET")&&op.matches("/manager/(policy|host|history|networks|volumes|events|jobs(?:/[a-f0-9]{32})?|containers/[a-f0-9]{64}/(?:metrics|history))");
        boolean write=method.equals("POST")&&(List.of("/manager/plans","/manager/execute").contains(op)||op.matches("/manager/jobs/[a-f0-9]{32}/cancel"));
        if(!read&&!write){Main.send(x,404,Map.of("error","Unsupported manager operation"));return true;}
        byte[] data=write?Main.J.writeValueAsBytes(Main.body(x)):null;
        if(write)Main.audit(actor,op,path,"requested");
        try{Object value=call(node,op,method,data,actor,role);if(write)Main.audit(actor,op,path,"accepted");Main.send(x,200,value);}
        catch(Exception failure){if(write)Main.audit(actor,op,path,"rejected");throw failure;}
        return true;
    }

    static Object call(Map<String,Object> node,String op,String method,byte[] body,String actor,String role)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(node.get("url")+op)).timeout(Duration.ofSeconds(30))
                .header("Authorization","Bearer "+node.get("token")).header("X-Ops-Actor",actor).header("X-Ops-Role",role)
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var response=Main.HTTP.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalArgumentException(response.body());
        return Main.J.readValue(response.body(),Object.class);
    }
}
