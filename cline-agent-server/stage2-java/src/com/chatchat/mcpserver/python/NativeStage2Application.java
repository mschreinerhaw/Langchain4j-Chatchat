package com.chatchat.mcpserver.python;

import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.security.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Isolated bootstrap of existing Runtime journal and managed Python; no component scan or new journal schema. */
public final class NativeStage2Application {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static JsonNode config;
    @Configuration @EnableTransactionManagement
    @EnableJpaRepositories(basePackages="com.chatchat.mcpserver.python")
    public static class Persistence {
        @Bean DataSource dataSource(){return new DriverManagerDataSource(config.path("jdbcUrl").asText(),config.path("dbUser").asText(),config.path("dbPassword").asText());}
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds){
            var f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);f.setPackagesToScan("com.chatchat.mcpserver.python");
            f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none","hibernate.show_sql","false"));return f;
        }
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory emf){return new JpaTransactionManager(emf);}
    }
    public static void main(String[] args)throws Exception{
        config=JSON.readTree(Files.readAllBytes(Path.of(System.getenv("CLINE_OPERATION_CONFIG"))));
        var context=new AnnotationConfigApplicationContext(Persistence.class);var ds=context.getBean(DataSource.class);
        var jdbc=new JdbcTemplate(ds);var transactions=new DataSourceTransactionManager(ds);
        var credentials=new InternalCredentialProperties();credentials.setCryptoKey(config.path("cryptoKey").asText());
        credentials.setEncryptedSecret(InternalSecretCipher.encrypt(config.path("internalSecret").asText(),config.path("cryptoKey").asText()));
        var props=new PythonRuntimeProperties();props.setWorkspaceRoot(config.path("pythonWorkspace").asText());props.setDataRoot(config.path("pythonData").asText());
        var data=new PythonDataFileService(props,credentials,JSON);var runtime=new PythonDockerRuntime(props,JSON,data);
        var workspace=new PythonOperationWorkspace(props,data,JSON);
        // Real Spring Data repositories, actual executor and immutable receipts. Unused admin collaborators are not exposed.
        var capability=new PythonCapabilityService(context.getBean(PythonEnvironmentRepository.class),context.getBean(PythonTemplateAssetRepository.class),
            context.getBean(PythonRuntimeExecutionRepository.class),runtime,credentials,null,JSON,null,null,data);
        var managed=new PythonManagedExecutionService(jdbc,transactions,capability,runtime,workspace,credentials,JSON);
        McpPythonControlPlanePort python=new LocalManagedPort(capability,managed,credentials);
        var store=new JdbcRuntimeEvidenceStore(jdbc,JSON,transactions);
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(2);scheduler.initialize();
        var authority=new NativeOperationAuthority(config,store,python,JSON,scheduler);
        var maintenance=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        maintenance.scheduleWithFixedDelay(()->{try{managed.recoverPending();}catch(Exception ignored){}},1,1,java.util.concurrent.TimeUnit.SECONDS);
        authority.listen();Runtime.getRuntime().addShutdownHook(new Thread(()->{maintenance.shutdownNow();managed.shutdown();scheduler.shutdown();context.close();}));
    }
    static final class LocalManagedPort implements McpPythonControlPlanePort{
        final PythonCapabilityService capability;final PythonManagedExecutionService managed;final InternalCredentialProperties credentials;
        LocalManagedPort(PythonCapabilityService c,PythonManagedExecutionService m,InternalCredentialProperties p){capability=c;managed=m;credentials=p;}
        public List<EnvironmentView> environments(){return capability.environments(true).stream().map(this::view).toList();}
        private EnvironmentView view(PythonEnvironment e){return new EnvironmentView(e.getId(),e.getName(),e.getDescription(),e.getDockerImage(),e.getPythonVersion(),e.getCpuLimit(),e.getMemoryLimit(),e.getDiskLimit(),e.getTmpfsLimit(),e.getRuntimeUser(),e.getNetworkPolicy(),e.getNetworkName(),e.getRequirementsJson(),e.getTimeoutSeconds(),e.isNetworkEnabled(),e.getVersionNumber(),e.getStatus());}
        public EnvironmentView environment(String id){return view(capability.managedEnvironmentState(id));}
        public OperationView start(StartRequest q){return managed.start(new PythonManagedExecutionService.StartCommand(q.tenantId(),q.ownerId(),q.runId(),q.taskId(),q.operationId(),q.attempt(),q.fencingToken(),q.environmentId(),q.assetId(),q.fileName(),InternalSecretCipher.encrypt(q.source(),credentials.resolvedSecret()),q.parameters(),q.inputSchemaJson(),q.authorizedFileIds(),q.authorizedFileHashes(),q.sourceSha256(),q.deadlineAt(),q.budgetReservationRef(),q.expectedEnvironmentVersion(),q.expectedImageDigest(),q.script()),q.tenantId(),q.ownerId());}
        public OperationView get(String t,String u,String r,String task,String op){return managed.get(t,u,r,task,op);}
        public OperationView cancel(String t,String u,String r,String task,String op,long f){return managed.cancel(t,u,r,task,op,f);}
        public byte[] artifact(String t,String u,String r,String task,String op,String channel,String file){return managed.artifact(t,u,r,task,op,channel,file);}
        public ProvisionResult provision(String e,String t,String u,String a){throw new UnsupportedOperationException();}
        public ExecutionResult preview(String e,String t,String u,String a,String f,String s,Map<String,Object> p,String j){throw new UnsupportedOperationException();}
        public SyncResult synchronizeTemplate(String id,TemplatePayload p){throw new UnsupportedOperationException();}
        public ExecutionResult executeTemplate(String id,String t,String u,Map<String,Object> p){throw new UnsupportedOperationException();}
        public void setTemplateEnabled(String id,boolean e){throw new UnsupportedOperationException();}
        public DataFileResult uploadDataFile(String t,String u,String id,String f,String h,byte[] b){throw new UnsupportedOperationException();}
        public byte[] downloadDataFile(String t,String u,String id){throw new UnsupportedOperationException();}
        public void deleteDataFile(String t,String u,String id){throw new UnsupportedOperationException();}
    }
}
