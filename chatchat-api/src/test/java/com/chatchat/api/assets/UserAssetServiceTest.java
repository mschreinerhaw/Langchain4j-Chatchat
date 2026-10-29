package com.chatchat.api.assets;

import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class UserAssetServiceTest {
    @Test void emptyPythonPackageMarkerRetainsExactBytesAndOwnerScope(){
        var file=assets.upload(alice,"__init__.py",new byte[0]);assertThat(file.size()).isZero();assertThat(assets.read(alice,file.id())).isEmpty();
        assertThatThrownBy(()->assets.read(bob,file.id())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->assets.upload(alice,"empty.csv",new byte[0])).hasMessageContaining("Empty document");
    }
    final UserAssetService.Owner alice=new UserAssetService.Owner("tenant","alice"),bob=new UserAssetService.Owner("tenant","bob");
    final byte[] data="date,amount\n2026-10-20,12.34\n".getBytes(StandardCharsets.UTF_8);
    JdbcTemplate jdbc;DataSourceTransactionManager tx;AssetStorageProperties config;MemoryBlobs blobs;UserAssetService assets;
    static class MemoryBlobs implements AssetBlobStore {
        Map<String,byte[]> bytes=new ConcurrentHashMap<>();boolean fail;boolean corrupt;
        public Stored put(String key,byte[] data,String type,String hash){if(fail)throw new IllegalStateException("injected outage");bytes.put(key,data.clone());return new Stored("v1");}
        public byte[] get(String key,String version,long limit){return corrupt?new byte[]{1}:bytes.get(key).clone();}
        public void delete(String key,String version){bytes.remove(key);}
    }
    @BeforeEach void setup(){
        var db=new DriverManagerDataSource("jdbc:h2:mem:assets-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("assets/schema.sql")).execute(db);
        jdbc=new JdbcTemplate(db);tx=new DataSourceTransactionManager(db);config=new AssetStorageProperties();
        config.setMaxFileBytes(1024);config.setUserQuotaBytes(2048);blobs=new MemoryBlobs();assets=new UserAssetService(jdbc,tx,blobs,config);
    }
    @Test void ownerAndTenantIsolationAndRestart(){
        var file=assets.upload(alice,"input.csv",data);
        assertThat(assets.read(alice,file.id())).isEqualTo(data);
        assertThatThrownBy(()->assets.read(bob,file.id())).hasMessageContaining("404");
        assertThatThrownBy(()->assets.read(new UserAssetService.Owner("other","alice"),file.id())).hasMessageContaining("404");
        assertThat(assets.list(bob,null,null,50)).isEmpty();
        assertThat(new UserAssetService(jdbc,tx,blobs,config).read(alice,file.id())).isEqualTo(data);
    }
    @Test void stableVersionDoesNotChargeAgainAndChangedBytesCreateNewVersion(){
        var one=assets.upload(alice,"input.csv",data);var same=assets.upload(alice,"input.csv",data);
        var changed=assets.upload(alice,"input.csv","a,b\n1,2".getBytes(StandardCharsets.UTF_8));
        assertThat(one.id()).isEqualTo(same.id()).isNotEqualTo(changed.id());
        assertThat(assets.read(alice,one.id())).isEqualTo(data);
        assertThat(assets.space(alice).usedBytes()).isEqualTo(data.length+7);
    }
    @Test void outageRetainsReservationAndRetriesWithoutDuplicateQuota(){
        blobs.fail=true;assertThatThrownBy(()->assets.upload(alice,"input.csv",data)).hasMessageContaining("outage");
        assertThat(assets.list(alice,null,null,50)).extracting(UserAssetService.File::state).containsExactly("STAGED");
        assertThat(assets.space(alice).usedBytes()).isEqualTo(data.length);
        blobs.fail=false;var file=assets.upload(alice,"input.csv",data);
        assertThat(file.state()).isEqualTo("AVAILABLE");assertThat(assets.space(alice).usedBytes()).isEqualTo(data.length);
    }
    @Test void integrityFailureNeverBecomesAvailable(){
        blobs.corrupt=true;assertThatThrownBy(()->assets.upload(alice,"input.csv",data)).hasMessageContaining("integrity");
        assertThat(assets.list(alice,null,null,50)).extracting(UserAssetService.File::state).containsExactly("STAGED");
    }
    @Test void concurrentQuotaCannotOverspend()throws Exception{
        var pool=Executors.newFixedThreadPool(4);
        try{
            var results=new ArrayList<Future<Boolean>>();
            for(int i=0;i<4;i++){final int n=i;results.add(pool.submit(()->{try{assets.upload(alice,"file"+n+".txt",new byte[800]);return true;}catch(org.springframework.web.server.ResponseStatusException full){return false;}}));}
            int accepted=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(2);assertThat(assets.space(alice).usedBytes()).isEqualTo(1600);
        }finally{pool.shutdownNow();}
    }
    @Test void taskFilesRequireExactTaskRunAndCannotBeDeleted()throws Exception{
        var file=assets.store(alice,UserAssetService.Kind.DATASET,"task","run","input:source","input.csv","text/csv",data);
        var scope=new KernelDataScope("tenant","alice",null,null,"run",null,Map.of());
        assertThat(assets.readTask(scope,"task",file.id())).isEqualTo(data);
        var output=new java.io.ByteArrayOutputStream();assets.copyTask(scope,"task",file.id(),output);assertThat(output.toByteArray()).isEqualTo(data);
        assertThatThrownBy(()->assets.readTask(scope,"different",file.id())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->assets.readTask(new KernelDataScope("tenant","alice",null,null,"other",null,Map.of()),"task",file.id())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->assets.delete(alice,file.id())).hasMessageContaining("409");
        blobs.corrupt=true;assertThatThrownBy(()->assets.copyTask(scope,"task",file.id(),new java.io.ByteArrayOutputStream())).hasMessageContaining("hash");
    }
    @Test void deleteIsIdempotentAndReleasesQuotaOnce(){
        var file=assets.upload(alice,"input.csv",data);assets.delete(alice,file.id());assets.delete(alice,file.id());
        assertThat(assets.space(alice).usedBytes()).isZero();assertThatThrownBy(()->assets.read(alice,file.id())).hasMessageContaining("404");
        var replacement=assets.upload(alice,"input.csv",data);assertThat(replacement.id()).isNotEqualTo(file.id());
        assertThat(assets.read(alice,replacement.id())).isEqualTo(data);assertThat(assets.space(alice).usedBytes()).isEqualTo(data.length);
    }
    @Test void rejectPathSpoofingExecutableAndMismatchedFormat(){
        assertThatThrownBy(()->assets.upload(alice,"../input.csv",data)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->assets.upload(alice,"payload.exe",data)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->assets.upload(alice,"fake.pdf",data)).hasMessageContaining("signature");
        assertThatThrownBy(()->assets.upload(alice,"large.csv",new byte[1025])).hasMessageContaining("413");
    }
    @Test void archiveDoesNotResolveOtherTasksOrOwners(){
        var archive=new RuntimeAssetArchive(assets,jdbc,new ObjectMapper());
        var scope=new KernelDataScope("tenant","alice",null,null,"run",null,Map.of());
        archive.save(scope,"task",UserAssetService.Kind.DATASET,"input:one","input.csv",data);
        assertThat(archive.read(scope,"task","input:one")).hasValueSatisfying(value->assertThat(value).isEqualTo(data));
        assertThat(archive.read(scope,"other","input:one")).isEmpty();
        assertThat(archive.read(new KernelDataScope("tenant","bob",null,null,"run",null,Map.of()),"task","input:one")).isEmpty();
    }
}
