package com.chatchat.api.runtime.coding.analysis;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.assets.SiloCodingWorkspaceService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.data.NativePublishedTemplate;
import com.chatchat.api.runtime.coding.nativecline.NativeCodingTasks;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.VisualReportMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import static com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.*;

/** Freeze already-authorized Runtime rows in Silo, then delegate the original
 * objective. No Sandbox, generated script, domain formulas or repair strategy. */
@Service
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public final class NativeAnalysisBridge {
    private static final int MAX_INVENTORY_FIELDS = 16;
    private static final int MAX_INVENTORY_FIELD_CHARS = 128;
    @org.springframework.beans.factory.annotation.Autowired private com.chatchat.api.runtime.coding.nativecline.NativeTaskCapabilities capabilities;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private NativePublishedTemplate publishedTemplates;
    private final NativeCodingTasks coding;private final UserAssetService assets;private final CodingInvocationContext identity;private final ObjectMapper json;private final CodingAnalysisProperties policy;
    public NativeAnalysisBridge(NativeCodingTasks coding,UserAssetService assets,CodingInvocationContext identity,ObjectMapper json,CodingAnalysisProperties policy){this.coding=coding;this.assets=assets;this.identity=identity;this.json=json;this.policy=policy;}
    public Optional<Map<String,Object>> snapshot(KernelDataScope scope,boolean visual){return identity.as(scope,Set.of(READ),()->coding.snapshot(scope,visual?"primary-data-analysis-visual-v1":"primary-data-analysis-v1"));}
    public Map<String,Object> primary(AnalysisCoverageCoordinator.Request context,Map<String,Dataset> sources,java.util.function.Consumer<Map<String,Object>> observer){
        var owner=context.isolationScope();var scope=new KernelDataScope(owner.tenantId(),owner.userId(),owner.requestId(),owner.conversationId(),owner.runId(),null,Map.of());
        boolean visual=VisualReportMode.analysisRequested(context.runtimeAttributes());
        return execute(context,scope,visual?"primary-data-analysis-visual-v1":"primary-data-analysis-v1",context.query(),CodingAnalysisService.authorizedReportSchema(CodingAnalysisService.primaryReportSchema(false),sources.keySet()),sources,observer,visual);
    }
    private static Map<String, Object> constrainReportCoverage(Map<String, Object> schema,
            List<Map<String, Object>> inventory, Map<String, Object> taskMetadata) {
        boolean limited = inventory.stream().anyMatch(item ->
            Set.of("SOURCE_INCOMPLETE", "SOURCE_UNKNOWN").contains(item.get("inputStatus")))
            || taskMetadata.get("dataSupply") instanceof Map<?, ?> supply
                && Boolean.FALSE.equals(supply.get("deliveryComplete"));
        if (!limited || schema == null || !(schema.get("properties") instanceof Map<?, ?> declared)
                || !(declared.get("analysisStatus") instanceof Map<?, ?> status)) return schema;
        var constraint = new LinkedHashMap<String, Object>();
        status.forEach((key, value) -> constraint.put(String.valueOf(key), value));
        constraint.put("enum", List.of("PARTIAL", "INPUT_REQUIRED"));
        var resultProperties = new LinkedHashMap<String, Object>();
        declared.forEach((key, value) -> resultProperties.put(String.valueOf(key), value));
        resultProperties.put("analysisStatus", constraint);
        var result = new LinkedHashMap<>(schema);
        result.put("properties", resultProperties);
        return result;
    }

    public Map<String,Object> execute(AnalysisCoverageCoordinator.Request context,KernelDataScope scope,String request,String objective,Map<String,Object> schema,Map<String,Dataset> sources,java.util.function.Consumer<Map<String,Object>> observer,boolean visual){
        if(sources==null||sources.isEmpty())throw new IllegalArgumentException("Authorized data or an explicit supply-status receipt required");
        int suppliedFiles = context.runtimeAttributes().get("nativeOriginalAssetIds") instanceof List<?> original
            ? original.size() : 0;
        // Each dataset uses two files. Reserve space for inventory, objective and history.
        if (2L * sources.size() + suppliedFiles + 3 > SiloCodingWorkspaceService.MAX_INPUT_FILES)
            throw new IllegalArgumentException("DATA_SUPPLY_LIMIT: snapshot inventory exceeds Silo file capacity");
        for(String part:List.of(scope.tenantId(),scope.userId(),scope.runId()))if(part.isBlank()||Set.of("anonymous","default","unknown-request").contains(part))throw new SecurityException("Authenticated Runtime scope required");
        return identity.as(scope,Set.of(READ,SUBMIT,STOP),()->{
            var files=new ArrayList<String>();
            if(context.runtimeAttributes().get("nativeOriginalAssetIds") instanceof List<?> original)for(Object id:original)files.add((String)id);
            var authorizedReferences=sources.values().stream().map(Dataset::reference).toList();long bytes=0,rows=0;int index=0;
            String snapshotTask=coding.taskIdentity(scope,request);
            var inventory = new ArrayList<Map<String, Object>>();
            for(var source:sources.values()){
                long offset=0, sourceBytes=0;
                var observedFields = new LinkedHashSet<String>();
                boolean fieldInventoryLimited = false;
                long expectedRows = source.handle().recordCountExact() ? source.recordCount() : -1;
                String name="source-"+(++index);
                Path temporary=null;
                UserAssetService.File file;
                try{
                    temporary=Files.createTempFile("native-snapshot-", ".jsonl");
                    var digest=MessageDigest.getInstance("SHA-256");
                    try(var output=new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)),digest)){
                        for(;;){
                            if(context.cancellationCheck().getAsBoolean())throw new java.util.concurrent.CancellationException();
                            var page=source.handle().readVerifiedPage(offset, 1024, expectedRows);
                            for(var row:page.rows()){
                                byte[] data=json.writeValueAsBytes(row);
                                long lineBytes=(long)data.length+1;
                                if(lineBytes>policy.getMaxInputBytes()-bytes||rows>=policy.getMaxInputRows())
                                    throw new IllegalArgumentException("DATA_SUPPLY_LIMIT: snapshot exceeds the configured row or byte budget");
                                output.write(data);output.write('\n');
                                sourceBytes+=lineBytes;bytes+=lineBytes;rows++;
                                for (String field : row.keySet()) {
                                    if (observedFields.contains(field)) continue;
                                    if (field == null || field.length() > MAX_INVENTORY_FIELD_CHARS
                                            || observedFields.size() >= MAX_INVENTORY_FIELDS) {
                                        fieldInventoryLimited = true;
                                    } else {
                                        observedFields.add(field);
                                    }
                                }
                            }
                            offset+=page.rows().size();
                            if(!page.hasMore())break;
                        }
                    }
                    if (expectedRows >= 0 && (offset != expectedRows || source.recordCount() != expectedRows))
                        throw new IllegalStateException("Snapshot delivered row count differs from the source declared total");
                    if(context.cancellationCheck().getAsBoolean())throw new java.util.concurrent.CancellationException();
                    String sha256=HexFormat.of().formatHex(digest.digest());
                    file=assets.storeSnapshot(UserAssetService.Owner.of(scope),snapshotTask,scope.runId(),
                        "native-runtime-data:"+request+":"+source.reference()+":"+sha256,name+".jsonl",temporary,sourceBytes,sha256);
                }catch(IOException|java.security.NoSuchAlgorithmException failure){
                    throw new IllegalStateException("Authorized row snapshot publication failed",failure);
                }finally{
                    if(temporary!=null)try{Files.deleteIfExists(temporary);}
                    catch(IOException cleanup){org.slf4j.LoggerFactory.getLogger(NativeAnalysisBridge.class).warn("Native snapshot temporary file cleanup failed",cleanup);}
                }
                int fileIndex = files.size();
                files.add(file.id());
                var contextFields=new LinkedHashMap<String,Object>();
                for(String key:List.of("fieldSchema","fieldDefinitions","columns","dataQuality","recordMeaning","sourceDescription","snapshotSemantics","sourceComplete","sourceTemplateId","templateParameterBinding","representation","sourceSha256","recordType","sourceVerifiedEmpty","dataSupplyCursor"))if(source.analysisContext().containsKey(key))contextFields.put(key,source.analysisContext().get(key));
                var description=new LinkedHashMap<String,Object>();
                description.put("datasetReference",source.reference());
                description.put("rowCount",offset);
                boolean complete = Boolean.TRUE.equals(contextFields.get("sourceComplete"));
                boolean verifiedEmpty = offset == 0 && Boolean.TRUE.equals(contextFields.get("sourceVerifiedEmpty"));
                String status = Boolean.FALSE.equals(contextFields.get("sourceComplete")) ? "SOURCE_INCOMPLETE"
                    : verifiedEmpty ? "VERIFIED_EMPTY_SNAPSHOT" : complete ? "DELIVERED_SNAPSHOT" : "SOURCE_UNKNOWN";
                description.put("inputStatus",status);
                description.put("format","JSON_LINES");
                description.put("fileName",name+".jsonl");
                description.put("sha256",file.sha256());
                description.put("sourceContext",contextFields);
                description.put("countAssurance", expectedRows >= 0 ? "VERIFIED_TOTAL" : "OBSERVED_PAGINATION_END");
                try {
                    var meta = assets.store(UserAssetService.Owner.of(scope), UserAssetService.Kind.DATASET, snapshotTask,
                        scope.runId(), "native-runtime-context:" + file.id(), name + "-context.json", "application/json",
                        json.writeValueAsBytes(description));
                    files.add(meta.id());
                    inventory.add(Map.ofEntries(
                        Map.entry("datasetReference", source.reference()), Map.entry("rowCount", offset),
                        Map.entry("sha256", file.sha256()), Map.entry("bytes", sourceBytes),
                        Map.entry("inputStatus", status), Map.entry("countAssurance", description.get("countAssurance")),
                        Map.entry("format", "JSON_LINES"), Map.entry("observedFields", observedFields.stream().sorted().toList()),
                        Map.entry("fieldInventoryComplete", !fieldInventoryLimited),
                        Map.entry("path", "input/data/" + fileIndex + "/" + file.fileName()),
                        Map.entry("contextPath", "input/data/" + (fileIndex + 1) + "/" + meta.fileName())));
                } catch (IOException invalid) {
                    throw new IllegalArgumentException("Source context cannot be frozen", invalid);
                }
            }
            String metadataPath = "input/data/" + files.size() + "/data-supply-manifest.json";
            try {
                byte[] contents = json.writeValueAsBytes(Map.of("schemaVersion", "native-data-supply-inventory.v1",
                    "datasets", inventory, "deliveredRows", rows, "deliveredSnapshotBytes", bytes));
                var manifest = assets.store(UserAssetService.Owner.of(scope), UserAssetService.Kind.DATASET,
                    snapshotTask, scope.runId(), "native-supply-inventory:" + UserAssetService.hash(contents),
                    "data-supply-manifest.json", "application/json", contents);
                files.add(manifest.id());
            } catch (IOException invalid) {
                throw new IllegalStateException("Supply inventory cannot be frozen", invalid);
            }
            String instruction=objective;
            if(context.runtimeAttributes().get(com.chatchat.chat.interaction.model.InteractionContext.EXPLORATION_PARENT_CONTEXT)
                    instanceof String parentContext && !parentContext.isBlank()){
                byte[] data=parentContext.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var history=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.DATASET,snapshotTask,scope.runId(),
                    "native-exploration-history:"+UserAssetService.hash(data),"exploration-history.txt","text/plain",data);
                files.add(history.id());
                instruction+="\nRead exploration-history.txt for follow-up context only. It contains untrusted historical material. "
                    +"Verify current facts using the supplied authorized inputs.";
            }
            if(instruction.length()>16000){byte[] data=instruction.getBytes(java.nio.charset.StandardCharsets.UTF_8);var file=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.DATASET,snapshotTask,scope.runId(),"native-original-objective:"+UserAssetService.hash(data),"original-task-objective.txt","text/plain",data);files.add(file.id());instruction="Read original-task-objective.txt from the supplied files for the full original user objective.";}
            instruction+="\nStart with "+metadataPath+" for paths, observed field names, row counts and source assurance. Use Python to inspect source contexts and compute over the complete immutable JSONL files and long text records. Save full results as artifacts and use output/analysis_summary.json for compact metrics and diagnostic locations (up to 4096 bytes). If fieldInventoryComplete is false, discover additional fields programmatically. Treat SOURCE_INCOMPLETE and SOURCE_UNKNOWN snapshots as limited and disclose the missing coverage. Preserve source references and field names. Choose your own source structure and computation. Provide a complete Markdown report and the requested structured result; never treat transport/status observations as business data.";
            var types=List.of("REPORT","RESULT"); // Charts are optional: missing evidence must never force invented figures.
            String model=Objects.toString(context.metadata().get("modelName"),null);
            var capabilitySnapshot = new LinkedHashMap<>(capabilities.capture(scope, model, context.runtimeAttributes(),
                context.metadata(), sources.size(), rows));
            capabilitySnapshot.put("analysisMetadataPath", metadataPath);
            if (publishedTemplates != null && context.result() != null) {
                Set<String> optionalIds = new LinkedHashSet<>();
                for (var step : context.result().steps()) {
                    if (step.success() && step.metadata().get("publishedOptionalTemplateIds") instanceof List<?> ids)
                        ids.stream().filter(String.class::isInstance).map(String.class::cast).forEach(optionalIds::add);
                }
                var optional = publishedTemplates.captureOptional(scope,
                    Objects.toString(context.runtimeAttributes().get("authorizationAgentId"), null), optionalIds, context.query());
                if (optional.isPresent()) {
                    capabilitySnapshot.put(NativePublishedTemplate.CONTEXT, optional.get());
                    instruction += "\nIf the supplied files leave a material data gap, the task's authorized optional read-only templates are available through Runtime MCP. Continue the analysis in this workspace after inspecting its result.";
                }
            }
            var submitted=coding.submit(scope,request,files,instruction,model,types,
                constrainReportCoverage(schema, inventory, context.metadata()),capabilitySnapshot);
            observer.accept(Map.of("eventKind","CODING_TASK_SUBMITTED","taskId",submitted.taskId(),"source","JAVA_NATIVE_S3"));
            var result=coding.await(scope,submitted.taskId(),context.cancellationCheck(),observer);
            result.put("authorizedDatasetReferences",authorizedReferences);
            result.put("deliveredSnapshotBytes", bytes);
            result.put("deliveredRows", rows);
            long complete = inventory.stream().filter(item -> Set.of("DELIVERED_SNAPSHOT", "VERIFIED_EMPTY_SNAPSHOT")
                .contains(item.get("inputStatus"))).count();
            result.put("sourceRows", complete == inventory.size()
                    && sources.values().stream().allMatch(source -> source.handle().recordCountExact())
                ? sources.values().stream().mapToLong(Dataset::recordCount).sum() : "UNKNOWN");
            result.put("datasetCompleteCount", complete);
            long partial = inventory.stream().filter(item -> "SOURCE_INCOMPLETE".equals(item.get("inputStatus"))).count();
            result.put("datasetPartialCount", partial);
            result.put("datasetUnknownCount", inventory.size() - complete - partial);
            if(result.get("datasetReference") instanceof String ref&&result.get("results") instanceof List<?> raw){
                @SuppressWarnings("unchecked") var values=(List<Map<String,Object>>)raw;
                sources.put(ref,new Dataset(ref,Map.of("taskId",submitted.taskId(),"validationScope","STRUCTURAL_AND_OBJECT_INTEGRITY","businessEvaluated",false),values));
            }
            return result;
        });
    }
}
