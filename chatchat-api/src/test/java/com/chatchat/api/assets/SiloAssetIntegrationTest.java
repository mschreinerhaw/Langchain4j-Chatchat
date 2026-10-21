package com.chatchat.api.assets;

import io.minio.*;
import io.minio.messages.VersioningConfiguration;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

/** Real pinned SILO container, versioned S3 transport and SQL registry. No emulated S3. */
@EnabledIfEnvironmentVariable(named="ASSET_SILO_INTEGRATION",matches="true")
class SiloAssetIntegrationTest {
    @Test void realVersionedObjectPersistsAndRejectsCrossOwner()throws Exception{
        var env=new HashMap<String,String>();
        for(String line:Files.readAllLines(Path.of("../target/assets-local/silo.env"))){var pair=line.split("=",2);env.put(pair[0],pair[1]);}
        var client=MinioClient.builder().endpoint("http://127.0.0.1:19000").credentials(env.get("MINIO_ROOT_USER"),env.get("MINIO_ROOT_PASSWORD")).build();
        String bucket="runtime-assets-integration";
        if(!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        client.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucket).config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED,null)).build());
        var config=new AssetStorageProperties();config.setEndpoint("http://127.0.0.1:19000");config.setAccessKey(env.get("MINIO_ROOT_USER"));config.setSecretKey(env.get("MINIO_ROOT_PASSWORD"));config.setBucket(bucket);
        var db=new DriverManagerDataSource("jdbc:h2:mem:silo-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("assets/schema.sql")).execute(db);
        var jdbc=new JdbcTemplate(db);var tx=new DataSourceTransactionManager(db);
        var service=new UserAssetService(jdbc,tx,new SiloAssetBlobStore(config),config);
        var owner=new UserAssetService.Owner("integration",UUID.randomUUID().toString());
        byte[] input="date,amount\n2026-10-20,42.50\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file=service.upload(owner,"input.csv",input);
        assertThat(jdbc.queryForObject("select object_version from runtime_workspace_file where id=?",String.class,file.id())).isNotBlank().isNotEqualTo("null");
        assertThat(new UserAssetService(jdbc,tx,new SiloAssetBlobStore(config),config).read(owner,file.id())).isEqualTo(input);
        var stored=jdbc.queryForMap("select object_key,object_version from runtime_workspace_file where id=?",file.id());
        var streamed=new java.io.ByteArrayOutputStream();new SiloAssetBlobStore(config).copy((String)stored.get("object_key"),(String)stored.get("object_version"),input.length,streamed);
        assertThat(streamed.toByteArray()).isEqualTo(input);
        assertThatThrownBy(()->service.read(new UserAssetService.Owner("integration","other"),file.id())).hasMessageContaining("404");
        var restarted=new ProcessBuilder("docker","restart","runtime-assets-silo-local").redirectErrorStream(true).start();
        assertThat(restarted.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(restarted.exitValue()).isZero();
        boolean ready=false;
        for(int i=0;i<30;i++){try{if(java.net.URI.create("http://127.0.0.1:19000/minio/health/ready").toURL().openConnection().getInputStream().readAllBytes()!=null){ready=true;break;}}catch(java.io.IOException waiting){Thread.sleep(500);}}
        assertThat(ready).isTrue();assertThat(service.read(owner,file.id())).isEqualTo(input);
        service.delete(owner,file.id());assertThat(service.space(owner).usedBytes()).isZero();
    }
}
