import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Runs inside the platform's private network namespace; requires no Python or CLI executor. */
public class BootstrapProbe {
    static final ObjectMapper JSON=new ObjectMapper();static HttpClient client;static String base;
    static JsonNode call(String path,JsonNode data)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20));
        if(data!=null)request.header("X-Ops-Request","1").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(data)));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new AssertionError("HTTP "+response.statusCode()+" "+path+" "+response.body());return JSON.readTree(response.body());
    }
    public static void main(String[] args)throws Exception {
        Path state=Path.of(args[0]);base="http://127.0.0.1:19280/api";client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String password="IsolatedJdkAcceptance2026!";boolean create=args[1].equals("create");
        if(create){if(!call("/setup",null).path("required").asBoolean())throw new AssertionError("Expected new installation");Properties properties=new Properties();try(var in=Files.newInputStream(state.resolve("standalone/instance.properties"))){properties.load(in);}call("/setup",JSON.createObjectNode().put("token",properties.getProperty("OPS_SETUP_TOKEN")).put("password",password));}
        call("/login",JSON.createObjectNode().put("username","admin").put("password",password));
        if(create)call("/entries",JSON.createObjectNode().put("name","isolated-jdk-persistence").put("url","http://127.0.0.1:19280"));
        boolean persistent=false;for(JsonNode entry:call("/entries",null))persistent|=entry.path("name").asText().equals("isolated-jdk-persistence");if(!persistent)throw new AssertionError("H2 entry missing");
        JsonNode health=call("/platform/health",null);if(!health.path("executorImplementation").asText().equals("JDK")||!health.path("database").asText().equals("UP"))throw new AssertionError("JDK/H2 platform health failed");
        JsonNode inventory=call("/inventory",null);if(inventory.isEmpty())throw new AssertionError("Missing configured node");for(JsonNode node:inventory)if(!node.has("error"))throw new AssertionError("Docker must actually be inaccessible");
        JsonNode metrics=call("/nodes/1/manager/host",null);if(!metrics.path("available").asBoolean()||metrics.path("memoryTotalBytes").asLong()<=0)throw new AssertionError("Actual host metrics unavailable without Docker");
        try(Socket probe=new Socket()){probe.connect(new InetSocketAddress("127.0.0.1",19400),1000);throw new AssertionError("Business network was not isolated");}catch(java.io.IOException expected){}
        System.out.println(JSON.writeValueAsString(Map.of("passed",true,"jdkOnly",true,"dockerInaccessible",true,"businessNetworkUnavailable",true,"h2PersistentEntry",true,"administratorLogin",true,"hostMetrics",true,"phase",args[1])));
    }
}
