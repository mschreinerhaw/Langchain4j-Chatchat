package com.chatchat.api.assets;

import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;
import static com.chatchat.api.assets.SiloCodingWorkspaceService.*;

/** Real SQL fencing/registry transactions; deterministic versioned transport.
 * Separate integration tests exercise actual Silo signing/ranges/composition.
 */
class SiloCodingWorkspaceServiceTest {
    JdbcTemplate jdbc;UserAssetService assets;SiloCodingWorkspaceService service;MemorySilo blobs;
    final KernelDataScope scope=new KernelDataScope("tenant-a","user-a","request",null,"run-a",null,Map.of());
    final UserAssetService.Owner owner=new UserAssetService.Owner("tenant-a","user-a");
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:node-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        setup(db);
    }
    void setup(DriverManagerDataSource db){
        new ResourceDatabasePopulator(new ClassPathResource("assets/schema.sql"),new ClassPathResource("runtime-evidence-schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);var tx=new DataSourceTransactionManager(db);var limits=new AssetStorageProperties();blobs=new MemorySilo();
        assets=new UserAssetService(jdbc,tx,blobs,limits);
        service=new SiloCodingWorkspaceService(assets,blobs,limits,new JdbcRuntimeEvidenceStore(jdbc,new ObjectMapper(),tx),new ObjectMapper());
    }
    @Test void feedbackFenceSurvivesFileDatabaseReopen(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory){
        var db=new DriverManagerDataSource("jdbc:h2:file:"+directory.resolve("control").toAbsolutePath()+";DATABASE_TO_LOWER=TRUE","sa","");
        setup(db);
        var state=create();byte[] first={1};var receipts=upload(state,delivery(state,"business/check.py",first),first);
        service.commit(scope,"task-a",state.epoch(),receipts);
        var failed=service.verifyBusinessCandidate(scope,"task-a",state.epoch(),v->new BusinessValidation("independent-check",false,Map.of("faults",List.of("METRIC_transactionRows"))));
        service.continueAfterValidationFailure(scope,"task-a",state.epoch(),failed.deliveryHash());
        // Every DriverManagerDataSource operation closes its connection; the
        // next service instance must see the committed fence, not old delivery.
        var reopened=new DriverManagerDataSource(db.getUrl(),"sa","");
        var nextJdbc=new JdbcTemplate(reopened);var nextTx=new DataSourceTransactionManager(reopened);var limits=new AssetStorageProperties();
        var nextAssets=new UserAssetService(nextJdbc,nextTx,blobs,limits);
        service=new SiloCodingWorkspaceService(nextAssets,blobs,limits,new JdbcRuntimeEvidenceStore(nextJdbc,new ObjectMapper(),nextTx),new ObjectMapper());
        assertThat(service.get(scope,"task-a").status()).isEqualTo("RUNNING");
        var old=delivery(state,"business/check.py",new byte[]{2});
        var revision=new Delivery(old.schemaVersion(),old.taskId(),old.runId(),old.tenantId(),old.inputSnapshotVersion(),"v2",old.sessionId(),old.epoch(),old.executionStatus(),old.acceptanceStatus(),old.artifacts(),old.nativeReceipts());
        assertThat(service.plan(scope,"task-a",state.epoch(),revision)).hasSize(1);
    }
    @Test void feedbackFenceSurvivesControlProcessExit(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)throws Exception{
        String url="jdbc:h2:file:"+directory.resolve("process-control").toAbsolutePath()+";DATABASE_TO_LOWER=TRUE";
        var arguments=directory.resolve("java.args");
        String classpath=System.getProperty("java.class.path").replace('\\','/');
        java.nio.file.Files.writeString(arguments,"-cp\n\""+classpath+"\"\n"+PersistenceProbe.class.getName()+"\n\""+url.replace('\\','/')+"\"\n");
        var log=directory.resolve("child.log");
        var child=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertThat(child.waitFor(30,TimeUnit.SECONDS)).as("control process must finish").isTrue();
        assertThat(child.exitValue()).as(java.nio.file.Files.readString(log)).isZero();
        var db=new DriverManagerDataSource(url,"sa","");var j=new JdbcTemplate(db);var tx=new DataSourceTransactionManager(db);var limits=new AssetStorageProperties();var memory=new MemorySilo();
        var reopened=new SiloCodingWorkspaceService(new UserAssetService(j,tx,memory,limits),memory,limits,new JdbcRuntimeEvidenceStore(j,new ObjectMapper(),tx),new ObjectMapper());
        assertThat(reopened.get(scope,"task-a").status()).isEqualTo("RUNNING");
        assertThat(reopened.get(scope,"task-a").delivery()).isNull();
    }
    public static final class PersistenceProbe {
        public static void main(String[] args){
            var fixture=new SiloCodingWorkspaceServiceTest();fixture.setup(new DriverManagerDataSource(args[0],"sa",""));
            var state=fixture.create();byte[] code={1};
            var receipt=fixture.upload(state,fixture.delivery(state,"business/check.py",code),code);
            fixture.service.commit(fixture.scope,"task-a",state.epoch(),receipt);
            var failed=fixture.service.verifyBusinessCandidate(fixture.scope,"task-a",state.epoch(),v->new BusinessValidation("independent-check",false,Map.of("faults",List.of("METRIC_transactionRows"))));
            fixture.service.continueAfterValidationFailure(fixture.scope,"task-a",state.epoch(),failed.deliveryHash());
            if(!"RUNNING".equals(fixture.service.get(fixture.scope,"task-a").status()))throw new AssertionError("State did not commit before exit");
            System.exit(0);
        }
    }
    State create(){var original=assets.upload(owner,"source.csv","value\n42\n".getBytes(StandardCharsets.UTF_8));return service.create(scope,"task-a",List.of(original.id()));}
    Delivery delivery(State state,String path,byte[] bytes){return new Delivery("coding_node_delivery.v1",state.taskId(),state.runId(),state.tenantId(),state.snapshotVersion(),"v1","native-session","native-epoch","NATIVE_TURN_COMPLETED","PENDING_RUNTIME_VALIDATION",List.of(new Artifact(path,"SOURCE",bytes.length,UserAssetService.hash(bytes))),List.of(new Receipt("1","turn.completed","native-session","native-epoch")));}
    List<Uploaded> upload(State state,Delivery delivery,byte[] bytes){
        var plans=service.plan(scope,state.taskId(),state.epoch(),delivery);var plan=plans.get(0);var parts=new ArrayList<UploadedPart>();
        for(var capability:plan.parts()){
            int start=capability.index()*CHUNK;byte[] chunk=Arrays.copyOfRange(bytes,start,Math.min(bytes.length,start+CHUNK));
            var result=blobs.put(capability.url().substring("put:".length()),chunk,"application/octet-stream",UserAssetService.hash(chunk));
            parts.add(new UploadedPart(capability.index(),chunk.length,UserAssetService.hash(chunk),result.versionId()));
        }
        return List.of(new Uploaded(plan.path(),parts,bytes.length,UserAssetService.hash(bytes)));
    }
    @Test void dynamicProjectRegistersImmutableVersionsOnceWithoutClaimingBusinessSuccess(){
        var state=create();byte[] code="print(42)".getBytes(StandardCharsets.UTF_8);var manifest=delivery(state,"customer/profit.py",code);
        var receipts=upload(state,manifest,code);var result=service.commit(scope,"task-a",state.epoch(),receipts);
        assertThat(result.status()).isEqualTo("RECEIVED_PENDING_BUSINESS_VALIDATION");assertThat(result.assetIds()).hasSize(2);
        assertThat(assets.read(owner,result.assetIds().get(0))).isEqualTo(code);
        long quota=assets.space(owner).usedBytes();assertThat(service.commit(scope,"task-a",state.epoch(),receipts)).isEqualTo(result);assertThat(assets.space(owner).usedBytes()).isEqualTo(quota);
        assertThat(assets.get(owner,result.assetIds().get(0)).fileName()).isEqualTo("profit.py");
    }
    @Test void cancellationAfterUploadRejectsLatePublicationAndPreservesInput(){
        var state=create();byte[] data={1,2,3};var receipts=upload(state,delivery(state,"output/result.json",data),data);
        var input=assets.readTask(scope,"task-a",state.inputs().get(0).assetId());service.cancel(scope,"task-a",state.epoch());
        assertThatThrownBy(()->service.commit(scope,"task-a",state.epoch(),receipts)).hasMessageContaining("fence");
        assertThat(jdbc.queryForObject("select count(*) from runtime_workspace_file where kind='CODE' or kind='ARTIFACT'",Integer.class)).isZero();
        assertThat(assets.readTask(scope,"task-a",state.inputs().get(0).assetId())).isEqualTo(input);
    }
    @Test void failedBusinessVerificationIsArchivedAndCannotBecomeCompleted(){
        var state=create();byte[] code={1};var receipts=upload(state,delivery(state,"business/check.py",code),code);
        service.commit(scope,"task-a",state.epoch(),receipts);
        var result=service.verifyBusiness(scope,"task-a",state.epoch(),verified->{
            assertThat(verified.inputs().values().iterator().next()).isEqualTo("value\n42\n".getBytes(StandardCharsets.UTF_8));
            assertThat(verified.artifacts().get("business/check.py")).isEqualTo(code);
            return new BusinessValidation("unit-test-independent-numeric-check",false,Map.of("expected",42,"actual",41));
        });
        assertThat(result.status()).isEqualTo("BUSINESS_VALIDATION_FAILED");assertThat(result.assetIds()).hasSize(3);
        assertThat(new String(assets.read(owner,result.assetIds().get(2)),StandardCharsets.UTF_8)).contains("\"passed\":false","\"expected\":42");
    }
    @Test void realValidationFeedbackRetainsFailedDeliveryAndAllowsANewVersionInSameTask(){
        var state=create();byte[] code={1};var receipt=upload(state,delivery(state,"business/check.py",code),code);
        service.commit(scope,"task-a",state.epoch(),receipt);
        var failed=service.verifyBusinessCandidate(scope,"task-a",state.epoch(),v->new BusinessValidation("independent-check",false,Map.of("faults",List.of("METRIC_transactionRows"))));
        var failedCode=failed.assetIds().get(0);var continued=service.continueAfterValidationFailure(scope,"task-a",state.epoch(),failed.deliveryHash());
        assertThat(continued.status()).isEqualTo("RUNNING");assertThat(continued.epoch()).isEqualTo(state.epoch());assertThat(continued.inputs()).isEqualTo(state.inputs());
        assertThat(assets.read(owner,failedCode)).isEqualTo(code);assertThat(continued.delivery()).isNull();
        var changed=delivery(state,"business/check.py",new byte[]{2});
        assertThatThrownBy(()->service.plan(scope,"task-a",state.epoch(),changed)).hasMessageContaining("immutable");
        var v2=new Delivery(changed.schemaVersion(),changed.taskId(),changed.runId(),changed.tenantId(),changed.inputSnapshotVersion(),"v2",changed.sessionId(),changed.epoch(),changed.executionStatus(),changed.acceptanceStatus(),changed.artifacts(),changed.nativeReceipts());
        assertThat(service.plan(scope,"task-a",state.epoch(),v2)).hasSize(1);
    }
    byte[] sealJournal()throws Exception{return new ObjectMapper().writeValueAsBytes(Map.of("events",List.of(
        Map.of("eventId","1","sessionId","native-session","epoch","native-epoch","eventType","turn.completed","payload",Map.of()),
        Map.of("eventId","2","sessionId","native-session","epoch","native-epoch","eventType","delivery.sealed","payload",Map.of("localStopConfirmed",true)))));}
    @Test void businessPassWaitsForActualStopObservationAndNativeSeal()throws Exception{
        var state=create();byte[] code={1};service.commit(scope,"task-a",state.epoch(),upload(state,delivery(state,"business/check.py",code),code));
        var candidate=service.verifyBusinessCandidate(scope,"task-a",state.epoch(),v->new BusinessValidation("independent-check",true,Map.of("factsVerified",true)));
        assertThat(candidate.status()).isEqualTo("BUSINESS_VALIDATED_PENDING_SEAL");byte[] journal=sealJournal();
        assertThatThrownBy(()->service.finalizeSealed(scope,"task-a",state.epoch(),candidate.deliveryHash(),journal,v->new StopObservation("a".repeat(64),false,"test-executor"))).hasMessageContaining("stop not confirmed");
        var finalState=service.finalizeSealed(scope,"task-a",state.epoch(),candidate.deliveryHash(),journal,v->new StopObservation("a".repeat(64),true,"test-executor"));
        assertThat(finalState.status()).isEqualTo("COMPLETED");assertThat(finalState.assetIds()).hasSize(4);
    }
    @Test void cancellationBetweenBusinessPassAndSealStillWins()throws Exception{
        var state=create();byte[] code={1};service.commit(scope,"task-a",state.epoch(),upload(state,delivery(state,"business/check.py",code),code));
        var candidate=service.verifyBusinessCandidate(scope,"task-a",state.epoch(),v->new BusinessValidation("independent-check",true,Map.of("factsVerified",true)));
        service.cancel(scope,"task-a",state.epoch());byte[] journal=sealJournal();
        assertThatThrownBy(()->service.finalizeSealed(scope,"task-a",state.epoch(),candidate.deliveryHash(),journal,v->new StopObservation("a".repeat(64),true,"test-executor"))).hasMessageContaining("awaiting seal");
        assertThat(service.get(scope,"task-a").status()).isEqualTo("CANCELLED");
        assertThatThrownBy(()->service.continueAfterValidationFailure(scope,"task-a",state.epoch(),candidate.deliveryHash())).hasMessageContaining("eligible");
    }
    @Test void tamperedOrIncompleteSiloObjectsNeverRegister(){
        var state=create();byte[] data={1,2,3};var receipts=upload(state,delivery(state,"output/result.json",data),data);
        blobs.objects.replaceAll((key,value)->new byte[]{9});
        assertThatThrownBy(()->service.commit(scope,"task-a",state.epoch(),receipts)).hasMessageContaining("length");
        assertThat(service.get(scope,"task-a").status()).isEqualTo("RUNNING");assertThat(service.get(scope,"task-a").assetIds()).isEmpty();
    }
    @Test void crossTenantTaskAndRunCannotMintReadCapabilitiesOrCommit(){
        var state=create();
        for(var foreign:List.of(new KernelDataScope("tenant-b","user-a","request",null,"run-a",null,Map.of()),new KernelDataScope("tenant-a","user-b","request",null,"run-a",null,Map.of()),new KernelDataScope("tenant-a","user-a","request",null,"run-b",null,Map.of())))
            assertThatThrownBy(()->service.inputCapabilities(foreign,"task-a",state.epoch())).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(()->service.inputCapabilities(scope,"task-b",state.epoch())).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(()->service.inputCapabilities(scope,"task-a","expired-epoch")).hasMessageContaining("fence");
    }
    @Test void composeMultipleChunksAndIndependentlyVerifyFinalObject(){
        var state=create();byte[] data=new byte[CHUNK+17];new Random(41).nextBytes(data);
        var receipts=upload(state,delivery(state,"business/model.bin",data),data);
        var result=service.commit(scope,"task-a",state.epoch(),receipts);assertThat(assets.read(owner,result.assetIds().get(0))).isEqualTo(data);assertThat(blobs.compositions).isEqualTo(1);
    }
    @Test void cancellationWinsConcurrentFinalizationAfterVerificationHasStarted()throws Exception{
        var state=create();byte[] data={1,2,3};var receipts=upload(state,delivery(state,"business/result.json",data),data);
        blobs.readStarted=new CountDownLatch(1);blobs.releaseRead=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();try{
            var pending=pool.submit(()->service.commit(scope,"task-a",state.epoch(),receipts));
            assertThat(blobs.readStarted.await(5,TimeUnit.SECONDS)).isTrue();service.cancel(scope,"task-a",state.epoch());blobs.releaseRead.countDown();
            assertThatThrownBy(()->pending.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(service.get(scope,"task-a").assetIds()).isEmpty();
        }finally{blobs.releaseRead.countDown();pool.shutdownNow();}
    }
    @Test void rejectReservedAndTraversalPathsAndImmutableDeliveryConflicts(){
        var state=create();byte[] data={1};
        for(String path:List.of("../x","input/x.parquet","/etc/passwd","foo/../x","foo\\x","C:/x"))assertThatThrownBy(()->service.plan(scope,"task-a",state.epoch(),delivery(state,path,data))).isInstanceOf(IllegalArgumentException.class);
        upload(state,delivery(state,"business/a.py",data),data);
        assertThatThrownBy(()->service.plan(scope,"task-a",state.epoch(),delivery(state,"business/b.py",data))).hasMessageContaining("immutable");
    }
    static class MemorySilo implements AssetBlobStore{
        Map<String,byte[]> objects=new ConcurrentHashMap<>();int compositions;CountDownLatch readStarted,releaseRead;
        public Stored put(String key,byte[] bytes,String type,String hash){String version=UUID.randomUUID().toString();objects.put(key+"@"+version,bytes.clone());return new Stored(version);}
        public byte[] get(String key,String version,long max){byte[] bytes=Objects.requireNonNull(objects.get(key+"@"+version));if(bytes.length>max)throw new IllegalStateException("length mismatch");return bytes.clone();}
        public void copy(String key,String version,long length,OutputStream target)throws IOException{
            if(readStarted!=null){readStarted.countDown();try{if(!releaseRead.await(10,TimeUnit.SECONDS))throw new IOException("test synchronization timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
            target.write(get(key,version,length));}
        public void delete(String key,String version){objects.remove(key+"@"+version);}
        public String presignRead(String key,String version,int seconds){return "get:"+key+"?versionId="+version;}
        public String presignWrite(String key,int seconds){return "put:"+key;}
        public ObjectInfo stat(String key,String version){return new ObjectInfo(version,objects.get(key+"@"+version).length);}
        public Stored compose(String key,List<ObjectPart> parts){compositions++;var buffer=new ByteArrayOutputStream();for(var p:parts)buffer.writeBytes(get(p.key(),p.versionId(),Integer.MAX_VALUE));return put(key,buffer.toByteArray(),"application/octet-stream","");}
    }
}
