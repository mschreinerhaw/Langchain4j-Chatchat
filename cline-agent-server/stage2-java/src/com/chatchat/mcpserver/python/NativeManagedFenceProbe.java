package com.chatchat.mcpserver.python;

import com.chatchat.common.security.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** Fault-injection CLI only. Calls the EXISTING managed executor against the isolated DB. */
public final class NativeManagedFenceProbe {
    public static void main(String[] args)throws Exception{
        var json=new ObjectMapper().findAndRegisterModules();var cfg=json.readTree(Files.readAllBytes(Path.of(System.getenv("CLINE_OPERATION_CONFIG"))));
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(cfg.path("jdbcUrl").asText(),cfg.path("dbUser").asText(),cfg.path("dbPassword").asText()));
        var credentials=new InternalCredentialProperties();credentials.setCryptoKey(cfg.path("cryptoKey").asText());credentials.setEncryptedSecret(InternalSecretCipher.encrypt(cfg.path("internalSecret").asText(),cfg.path("cryptoKey").asText()));
        var props=new PythonRuntimeProperties();props.setWorkspaceRoot(cfg.path("pythonWorkspace").asText());props.setDataRoot(cfg.path("pythonData").asText());
        var data=new PythonDataFileService(props,credentials,json);var runtime=new PythonDockerRuntime(props,json,data);var workspace=new PythonOperationWorkspace(props,data,json);
        var managed=new PythonManagedExecutionService(jdbc,new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())),null,runtime,workspace,credentials,json);
        var rows=jdbc.queryForList("select tenant_id,owner_id,run_id,task_id,operation_id,fencing_token from mcp_python_managed_operation where executor_stopped=true order by created_at desc limit 1");
        if(rows.isEmpty())throw new IllegalStateException("Real completed Python operation required");var row=rows.get(0);
        String t=row.get("tenant_id").toString(),u=row.get("owner_id").toString(),r=row.get("run_id").toString(),task=row.get("task_id").toString(),op=row.get("operation_id").toString();long old=((Number)row.get("fencing_token")).longValue();
        byte[] before=managed.artifact(t,u,r,task,op,"logs","receipt.json");var advanced=managed.cancel(t,u,r,task,op,old+1);boolean rejected=false;
        try{managed.cancel(t,u,r,task,op,old);}catch(IllegalStateException expected){rejected=expected.getMessage().equals("Fencing token is stale");}
        byte[] after=managed.artifact(t,u,r,task,op,"logs","receipt.json");
        System.out.println(json.writeValueAsString(Map.of("case","existing-managed-python-stale-fence","pass",rejected&&Arrays.equals(before,after),"operationId",op,"oldFence",old,"newFence",advanced.fencingToken(),"staleRejected",rejected,"immutableReceiptUnchanged",Arrays.equals(before,after))));managed.shutdown();
        if(!rejected||!Arrays.equals(before,after))throw new IllegalStateException("Managed fencing gate failed");
    }
}
