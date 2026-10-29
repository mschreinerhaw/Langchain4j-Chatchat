package com.chatchat.api.runtime.coding.data;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.CodingInputManifest.Format;
import com.chatchat.platform.runtime.coding.CodingSharedFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodingInputFilePublisherTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final CodingInvocationContext access=new CodingInvocationContext();
    final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
    McpPythonControlPlanePort python;CodingInputSnapshotService inputs;CodingInputFilesProperties policy;
    JdbcRuntimeEvidenceStore evidence;
    @BeforeEach void setup() {
        var db=new DriverManagerDataSource("jdbc:h2:mem:files-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(db);
        evidence=new JdbcRuntimeEvidenceStore(new JdbcTemplate(db),json,new DataSourceTransactionManager(db));
        python=mock(McpPythonControlPlanePort.class);
        when(python.inputCapabilities()).thenReturn(List.of("SHARED_DIRECTORY","PARQUET","CSV"));
        when(python.registerSharedDataFile(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong())).thenAnswer(call->{
            String tenant=call.getArgument(0),owner=call.getArgument(1),id=call.getArgument(2),name=call.getArgument(3),hash=call.getArgument(4);long size=call.getArgument(5);
            var file=new CodingSharedFiles(root).verify(tenant,owner,id,name,hash,size);
            return new McpPythonControlPlanePort.DataFileResult(id,file.toString(),"/data/input/"+id+"/"+name,size,hash,"AVAILABLE");
        });
        policy=new CodingInputFilesProperties();policy.setEnabled(true);policy.setSharedRoot(root.toString());policy.setPartRows(1);
        inputs=new CodingInputSnapshotService(evidence,python,access,json);
        inputs.setFilePublisher(new CodingInputFilePublisher(policy,python,evidence,access,json));
    }
    @ParameterizedTest @EnumSource(value=Format.class,names={"PARQUET","CSV"})
    void publishedInputsReplayAndIndependentReaderPreservesSource(Format format) throws Exception {
        policy.setFormat(format);
        var second=new LinkedHashMap<String,Object>();second.put("amount",new BigDecimal("0.0100"));second.put("label",null);
        var dataset=new Dataset("source",Map.of(),List.of(Map.of("amount",new BigDecimal("100.2500"),"label","original","identifier",9007199254740993L,
            "record",Map.of("precise",new BigDecimal("1234567890123.4500"),"identifier",9007199254740993L)),second));
        var snapshot=capture("task",dataset,()->{});assertThat(snapshot.chunked()).isTrue();assertThat(snapshot.chunks()).hasSize(3);
        assertThat(capture("task",dataset,()->{})).isEqualTo(snapshot);
        var descriptor=access.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->inputs.describe(scope,"task",snapshot));
        assertThat(descriptor).containsEntry("format",format.name()).containsEntry("delivery","SHARED_DIRECTORY");
        var contract=access.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->contract(snapshot));
        assertThat(access.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->inputs.requireAuthorized(contract))).isEqualTo(snapshot);
        assertThat(descriptor.toString()).doesNotContain(root.toString(),"100.2500","original");
        var restored=new ArrayList<Map<String,Object>>();
        CodingInputRows.scan(snapshot,file->{try{return Files.readAllBytes(new CodingSharedFiles(root)
            .verify("tenant","owner",file.fileId(),file.fileName(),file.sha256(),file.size()));}
            catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}},json,restored::add);
        assertThat(restored).hasSize(2);assertThat(restored.get(0)).containsEntry("amount",new BigDecimal("100.2500"));
        assertThat(restored.get(1)).containsEntry("label",null);
        assertThat(restored.get(0)).containsEntry("identifier",9007199254740993L);assertThat(restored.get(1)).doesNotContainKey("identifier");
        var nested=(Map<?,?>)restored.get(0).get("record");
        assertThat((BigDecimal)nested.get("precise")).isEqualByComparingTo(new BigDecimal("1234567890123.4500"));
        assertThat(nested.get("identifier")).isEqualTo(9007199254740993L);
        verify(python,never()).uploadDataFile(anyString(),anyString(),anyString(),anyString(),anyString(),any());
        String export=System.getProperty("coding.format.fixtureRoot");
        if(export!=null) {
            Path directory=Path.of(export).resolve(format.name());Files.createDirectories(directory);
            for(var file:CodingInputSnapshotService.mountedFiles(snapshot)) {
                Path destination=directory.resolve(file.fileId());Files.createDirectories(destination);
                Files.copy(new CodingSharedFiles(root).verify("tenant","owner",file.fileId(),file.fileName(),file.sha256(),file.size()),
                    destination.resolve(file.fileName()),StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(directory.resolve("root.json"),json.writeValueAsBytes(snapshot));
        }
    }
    @Test void unsupportedOrCancelledCaptureCannotPublishManifestEvidence() {
        var dataset=new Dataset("source",Map.of(),List.of(Map.of("value",1)));
        when(python.inputCapabilities()).thenReturn(List.of("JSON_LINES"));
        assertThatThrownBy(()->capture("task",dataset,()->{})).hasMessageContaining("negotiated");
        when(python.inputCapabilities()).thenReturn(List.of("SHARED_DIRECTORY","PARQUET"));
        assertThatThrownBy(()->capture("task",dataset,()->{throw new java.util.concurrent.CancellationException();}))
            .isInstanceOf(java.util.concurrent.CancellationException.class);
        verify(python,never()).registerSharedDataFile(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong());
    }
    @Test void originalSqlIsMountedByteExactAndTamperingCannotPassSnapshotVerification() throws Exception {
        byte[] bytes="-- untrusted source, not execution authority\nselect 'actual text';\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash=com.chatchat.api.assets.UserAssetService.hash(bytes);
        var row=Map.<String,Object>of("role","SOURCE_FILE","fileName","source.sql","size",bytes.length,"sha256",hash,
            "chunkIndex",0,"chunkCount",1,"chunkSha256",hash,"contentBase64",Base64.getEncoder().encodeToString(bytes));
        var snapshot=capture("native",new Dataset("source",Map.of("representation","BASE64_ORIGINAL_FILE_CHUNKS"),List.of(row)),()->{});
        var cache=new CodingSharedFiles(root);
        java.util.function.Function<CodingInputSnapshotService.Chunk,byte[]> fetch=file->{try {
            return Files.readAllBytes(cache.verify("tenant","owner",file.fileId(),file.fileName(),file.sha256(),file.size()));
        }catch(java.io.IOException invalid){throw new SecurityException(invalid);}};
        var manifest=json.readTree(fetch.apply(new CodingInputSnapshotService.Chunk(snapshot.fileId(),snapshot.fileName(),snapshot.sha256(),snapshot.rowCount(),snapshot.size())));
        var declared=manifest.path("nativeFiles").get(0);
        var original=snapshot.chunks().stream().filter(f->f.fileId().equals(declared.path("fileId").asText())).findFirst().orElseThrow();
        assertThat(original.fileName()).isEqualTo("original-0.sql");
        assertThat(fetch.apply(original)).isEqualTo(bytes);
        assertThat(declared.path("originalName").asText()).isEqualTo("source.sql");
        assertThat(declared.path("format").asText()).isEqualTo("TEXT");
        assertThat(declared.path("readOnly").asBoolean()).isTrue();
        assertThat(declared.path("virtualPath").asText()).isEqualTo("/data/input/"+original.fileId()+"/"+original.fileName());
        var restored=json.treeToValue(manifest,com.chatchat.common.runtime.coding.CodingInputManifest.class);
        assertThat(restored.nativeFiles().get(0).virtualPath()).isEqualTo(declared.path("virtualPath").asText());
        CodingInputRows.scan(snapshot,fetch,json,ignored->{});
        Files.write(cache.directory("tenant","owner",original.fileId()).resolve(original.fileName()),new byte[]{1});
        assertThatThrownBy(()->CodingInputRows.scan(snapshot,fetch,json,ignored->{})).isInstanceOf(SecurityException.class);
    }
    @Test void legacyManifestWithoutNativeFilesRemainsReadable() throws Exception {
        var snapshot=capture("legacy",new Dataset("source",Map.of(),List.of(Map.of("value",1))),()->{});
        var cache=new CodingSharedFiles(root);
        var document=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(
            cache.verify("tenant","owner",snapshot.fileId(),snapshot.fileName(),snapshot.sha256(),snapshot.size())));
        document.remove("nativeFiles");
        assertThat(json.treeToValue(document,com.chatchat.common.runtime.coding.CodingInputManifest.class).nativeFiles()).isEmpty();
    }
    @Test void combinedSnapshotPreservesEachOriginalAndTheEntireLongMessage() throws Exception {
        byte[] original="id,amount\nA,12.30\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash=com.chatchat.api.assets.UserAssetService.hash(original);
        String message="Original objective and pasted material. ".repeat(300);
        var record=Map.<String,Object>of("role","SOURCE_FILE","fileName","data.csv","size",original.length,"sha256",hash,
            "chunkIndex",0,"chunkCount",1,"chunkSha256",hash,"contentBase64",Base64.getEncoder().encodeToString(original));
        var rows=List.<Map<String,Object>>of(Map.of("datasetReference","user-asset:one","record",record),
            Map.of("datasetReference","runtime-user-message:run","record",Map.of("text",message,"sha256",com.chatchat.api.assets.UserAssetService.hash(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        var snapshot=access.as(scope,EnumSet.of(CodingTaskAccess.Action.SUBMIT),()->inputs.captureConfigured(scope,"batch",
            List.of(new Dataset("coding-input-set:batch",Map.of("inputVersions",List.of()),rows)),"coding-input-set:batch","decision","workspace",100,1024*1024,()->{}));
        var cache=new CodingSharedFiles(root);
        var manifest=json.readTree(Files.readAllBytes(cache.verify("tenant","owner",snapshot.fileId(),snapshot.fileName(),snapshot.sha256(),snapshot.size())));
        assertThat(manifest.path("nativeFiles").size()).isEqualTo(2);
        var descriptor=access.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->inputs.describe(scope,"batch",snapshot));
        assertThat(descriptor.get("nativeFileSchemas").toString()).contains("columns","amount","user-asset:one")
            .doesNotContain("A,12.30");
        for(var file:manifest.path("nativeFiles")) {
            byte[] bytes=Files.readAllBytes(cache.verify("tenant","owner",file.path("fileId").asText(),file.path("fileName").asText(),file.path("sha256").asText(),file.path("sizeBytes").asLong()));
            assertThat(bytes).isEqualTo(file.path("sourceDatasetRef").asText().equals("user-asset:one")?original:message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    @Test void pastedMaterialsKeepNativeUtf8BytesWithoutAHiddenReaderDependency() throws Exception {
        String message="instruction\n"+"untrusted source log\n".repeat(2000);
        var source="runtime-context:pasted";
        var row=Map.<String,Object>of("schemaVersion","analysis-materials.v1","objective","read original material",
            "materials",List.of(Map.of("kind","originalUserMessage","text",message)));
        var snapshot=access.as(scope,EnumSet.of(CodingTaskAccess.Action.SUBMIT),()->inputs.captureConfigured(scope,"paste",
            List.of(new Dataset(source,Map.of(),List.of(row))),source,"decision","workspace",100,1024*1024,()->{}));
        var cache=new CodingSharedFiles(root);
        var manifest=json.readTree(Files.readAllBytes(cache.verify("tenant","owner",snapshot.fileId(),snapshot.fileName(),snapshot.sha256(),snapshot.size())));
        var nativeFile=manifest.path("nativeFiles").get(0);
        byte[] bytes=Files.readAllBytes(cache.verify("tenant","owner",nativeFile.path("fileId").asText(),nativeFile.path("fileName").asText(),nativeFile.path("sha256").asText(),nativeFile.path("sizeBytes").asLong()));
        assertThat(bytes).isEqualTo(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(nativeFile.path("sourceDatasetRef").asText()).isEqualTo(source);
        assertThat(nativeFile.path("fileName").asText()).endsWith(".txt");
        var description=access.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->inputs.describe(scope,"paste",snapshot));
        assertThat(description.get("nativeFileSchemas").toString()).contains("ORIGINAL_USER_MESSAGE_TEXT","UTF-8","not an analysis-materials.v1 JSON envelope");
    }
    @ParameterizedTest @EnumSource(value=Format.class,names={"PARQUET","CSV"})
    void emptySourcesProduceReadableZeroRowFiles(Format format) throws Exception {
        var declared=Map.of("definition",Map.of("type","object","properties",Map.of("amount",Map.of("type","number"))));
        policy.setFormat(format);var snapshot=capture("empty",new Dataset("source",Map.of("schema",declared),List.of()),()->{});
        var restored=new ArrayList<Map<String,Object>>();
        CodingInputRows.scan(snapshot,file->{try{return Files.readAllBytes(new CodingSharedFiles(root)
            .verify("tenant","owner",file.fileId(),file.fileName(),file.sha256(),file.size()));}
            catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}},json,restored::add);
        assertThat(snapshot.rowCount()).isZero();assertThat(restored).isEmpty();
        var descriptor=access.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->inputs.describe(scope,"empty",snapshot));
        assertThat(json.readTree((String)descriptor.get("schemaJson")).path("declaredSourceSchema"))
            .isEqualTo(json.valueToTree(declared));
    }
    @Test void filePolicyIsFrozenAtCaptureStart() {
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        var snapshot=capture("frozen",new Dataset("source",Map.of(),List.of(Map.of("value",1))),()->{
            if(changed.compareAndSet(false,true))policy.setFormat(Format.CSV);
        });
        var descriptor=access.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->inputs.describe(scope,"frozen",snapshot));
        assertThat(descriptor).containsEntry("format","PARQUET");
    }
    @Test void onlyDeclaredLeasedCodingContextReceivesBoundedVerifiedObservation() throws Exception {
        var data=new Dataset("source",Map.of(),List.of(Map.of("text","PRIVATE_AUTHORIZED_SAMPLE"),Map.of("text","SECOND_ROW_NOT_PREVIEWED")));
        var snapshot=capture("task",data,()->{});
        var grants=EnumSet.of(CodingTaskAccess.Action.READ,CodingTaskAccess.Action.WORKER);
        var declared=access.as(scope,grants,()->contract(snapshot,List.of(CodingInputObservation.POLICY_REF)));
        var legacy=access.as(scope,grants,()->contract(snapshot));
        assertThatThrownBy(()->access.as(scope,grants,()->inputs.observeForCoding(legacy))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->access.as(scope,EnumSet.of(CodingTaskAccess.Action.READ),()->inputs.observeForCoding(declared)))
            .isInstanceOf(com.chatchat.common.runtime.coding.CodingContractException.class);
        var observation=access.as(scope,grants,()->inputs.observeForCoding(declared));
        assertThat(observation).containsEntry("complete",false).containsEntry("inputSha256",snapshot.sha256());
        assertThat(observation.toString()).contains("PRIVATE_AUTHORIZED_SAMPLE").doesNotContain("SECOND_ROW_NOT_PREVIEWED");
        assertThat(access.as(scope,grants,()->inputs.describe(scope,"task",snapshot)).toString())
            .doesNotContain("PRIVATE_AUTHORIZED_SAMPLE","SECOND_ROW_NOT_PREVIEWED");
        var part=snapshot.chunks().get(1);
        Files.write(new CodingSharedFiles(root).directory("tenant","owner",part.fileId()).resolve(part.fileName()),new byte[]{1});
        assertThatThrownBy(()->access.as(scope,grants,()->inputs.observeForCoding(declared))).isInstanceOf(SecurityException.class);
    }
    @Test void taskAuthorizedMissingCacheRestoresOnlyHashBoundFilesFromArchive() throws Exception {
        var archive=mock(com.chatchat.api.assets.RuntimeAssetArchive.class);
        var saved=new HashMap<String,byte[]>();
        when(archive.save(any(),eq("task"),any(),anyString(),anyString(),any())).thenAnswer(call->{saved.put(call.getArgument(3),call.getArgument(5));return null;});
        when(archive.read(eq(scope),eq("task"),anyString())).thenAnswer(call->Optional.ofNullable(saved.get(call.getArgument(2))));
        var publisher=new CodingInputFilePublisher(policy,python,evidence,access,json);
        publisher.setAssets(archive);inputs.setFilePublisher(publisher);
        var snapshot=capture("task",new Dataset("source",Map.of(),List.of(Map.of("value",42))),()->{});
        var file=snapshot.chunks().get(1);var cache=new CodingSharedFiles(root);
        Path path=cache.verify("tenant","owner",file.fileId(),file.fileName(),file.sha256(),file.size());
        Files.delete(path);Files.delete(path.getParent());
        var grants=EnumSet.of(CodingTaskAccess.Action.READ);
        var contract=access.as(scope,grants,()->contract(snapshot));
        access.as(scope,grants,()->inputs.requireAuthorized(contract));
        assertThat(cache.verify("tenant","owner",file.fileId(),file.fileName(),file.sha256(),file.size())).isRegularFile();
        verify(archive).read(scope,"task","input:"+file.fileId());
    }
    private CodingInputSnapshotService.Snapshot capture(String task,Dataset dataset,Runnable guard) {
        return access.as(scope,EnumSet.of(CodingTaskAccess.Action.SUBMIT),()->inputs.captureConfigured(scope,task,List.of(dataset),
            "source","decision","workspace",100,1024*1024,guard));
    }
    private com.chatchat.common.runtime.coding.CodingTaskContract contract(CodingInputSnapshotService.Snapshot input) {
        return contract(input,List.of());
    }
    private com.chatchat.common.runtime.coding.CodingTaskContract contract(CodingInputSnapshotService.Snapshot input,List<String> metadataRefs) {
        var c=com.chatchat.common.runtime.coding.CodingProtocol.ANALYSIS_VERSION;
        return new com.chatchat.common.runtime.coding.CodingTaskContract(c,"task",scope,
            new com.chatchat.common.runtime.coding.CodingTaskContract.Objective("calculate",List.of("rows.jsonl"),List.of("rule:1")),
            input.reference(),metadataRefs,"workspace",
            new com.chatchat.common.runtime.coding.CodingTaskContract.Authorization("decision",List.of("python_execute"),
                CodingInputSnapshotService.mountedFiles(input).stream().map(CodingInputSnapshotService.Chunk::fileId).toList(),List.of(),List.of(),
                com.chatchat.common.runtime.coding.CodingTaskContract.NetworkPolicy.NONE,List.of(),
                List.of(com.chatchat.common.runtime.coding.CodingTaskContract.SideEffect.CODE_EXECUTION)),
            new com.chatchat.common.runtime.coding.CodingTaskContract.Budget("budget:1",java.time.Instant.now().plusSeconds(120)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS),1,2048,1,1,"1","USD",1000,1024*1024,10000),
            "env:1","rule:1",new com.chatchat.common.runtime.coding.CodingTaskContract.OutputContract(
                com.chatchat.common.runtime.coding.CodingTaskContract.ResultFormat.JSON_LINES,"schema:1","schema:2",List.of("rows.jsonl"),
                List.of("application/x-ndjson"),100,10000,List.of(com.chatchat.common.runtime.coding.CodingTaskContract.RequiredEvidence.values())),
            inputs.withInputContext(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT"),scope,"task",input));
    }
}
