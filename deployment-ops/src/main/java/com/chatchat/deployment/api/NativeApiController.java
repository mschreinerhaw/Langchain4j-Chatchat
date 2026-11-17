package com.chatchat.deployment.api;

import com.sun.net.httpserver.HttpExchange;
import jakarta.servlet.http.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import com.fasterxml.jackson.databind.ObjectMapper;

@Controller
public class NativeApiController {
    private final Method handler;
    private final ObjectMapper json;
    public NativeApiController(DataSource dataSource, Environment env,ObjectMapper json, NativeManagementRoutes routes) throws Exception {
        this.json=json;
        Class<?> api=Class.forName("Main");
        String schema=new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
        api.getMethod("initializeNative",DataSource.class,String.class,String.class,String.class,String.class)
            .invoke(null,dataSource,schema,env.getProperty("ADMIN_PASSWORD"),env.getProperty("EXECUTOR_TOKEN"),env.getProperty("OPS_EXECUTOR_URL","http://127.0.0.1:19242"));
        handler=api.getMethod("handleNative",HttpExchange.class);
        api.getMethod("registerNativeRoutes",Object.class).invoke(null,routes);
    }
    @RequestMapping("/api/**")
    public void handle(HttpServletRequest request,HttpServletResponse response) throws Exception {
        try {com.chatchat.deployment.storage.StorageGuard.verifyRuntime();}
        catch(java.io.IOException error){response.setStatus(503);response.setContentType("application/json");json.writeValue(response.getOutputStream(),java.util.Map.of("error","Required data mount is unavailable; refusing database and filesystem operations"));return;}
        response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        try {handler.invoke(null,new ServletExchange(request,response));}
        catch(InvocationTargetException exception){
            if(response.isCommitted())return;
            response.setStatus(exception.getCause() instanceof IllegalArgumentException?400:502);
            response.setContentType("application/json");
            json.writeValue(response.getOutputStream(),java.util.Map.of("error",exception.getCause() instanceof IllegalArgumentException?String.valueOf(exception.getCause().getMessage()):"Operation failed; inspect platform logs and node status"));
        }
    }
}
