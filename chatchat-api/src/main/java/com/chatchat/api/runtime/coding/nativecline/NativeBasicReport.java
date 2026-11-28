package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.agents.orchestration.analysis.graph.WorkspaceResultSchema;
import com.chatchat.api.assets.SiloCodingWorkspaceService;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.knowledge.KnowledgeDisclosureGuard;
import com.chatchat.common.runtime.coding.CodingRuntimeContext;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.runtime.coding.CodingTaskOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Program-owned reports from Runtime data snapshots before analysis and after a reconciled Native failure. */
@Service
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public class NativeBasicReport {
    static final String LEVEL = "DETERMINISTIC_BASIC_REPORT";
    private static final String RECEIPT = "runtime-basic-report-receipt:v1";
    private static final String OUTPUT = "runtime-basic-report:v1:";
    private static final String STAGE_SCHEMA="runtime_basic_report_stage.v1";
    private static final int MAX_INVENTORY_BYTES=4*1024*1024;
    private final UserAssetService assets;
    private final NativeSiloTaskService capture;
    private final SiloCodingWorkspaceService workspace;
    private final KnowledgeDisclosureGuard disclosure;
    private final ObjectMapper json;

    public NativeBasicReport(UserAssetService assets,NativeSiloTaskService capture,SiloCodingWorkspaceService workspace,
            KnowledgeDisclosureGuard disclosure,ObjectMapper json) {
        this.assets=assets;this.capture=capture;this.workspace=workspace;this.disclosure=disclosure;this.json=json;
    }

    static boolean eligible(CodingTaskOutcome outcome) {
        return Set.of(CodingTaskOutcome.Status.FAILED,CodingTaskOutcome.Status.BUDGET_EXCEEDED).contains(outcome.status())
            &&NativeCodingTasks.canExposeSupplyEvidence(outcome)
            &&outcome.executionControl().executorStopped()
            &&outcome.resultDatasetRef()==null;
    }

    record Delivered(Map<String,Object> row,List<UserAssetService.File> files,Map<String,Object> proof) {}

    @Transactional
    public Optional<Delivered> publish(CodingTaskContract task,CodingTaskOutcome outcome,BooleanSupplier mayPublish) {
        if (!eligible(outcome) || !supportsBasicReport(task)) return Optional.empty();
        var scope=task.scope();
        var owner=UserAssetService.Owner.of(scope);
        requireActive(mayPublish);
        disclosure.checkRun(scope.tenantId(),scope.userId(),scope.runId());
        var existing=read(task,outcome);
        if (existing.isPresent()) return existing;
        var draft=build(task,outcome.reasonCode().name(),outcome.endedAt()).orElse(null);
        if (draft==null) return Optional.empty();
        var row=draft.row();
        var source=draft.inventory();
        var factsAsset=draft.facts();
        String markdownPath=draft.markdownPath(),resultPath=draft.resultPath();
        requireActive(mayPublish);
        byte[] markdownBytes=row.get("reportMarkdown").toString().getBytes(StandardCharsets.UTF_8);
        byte[] structuredBytes=bytes(row);
        if ((long)markdownBytes.length+structuredBytes.length>task.outputContract().maxResultBytes()) return Optional.empty();
        var markdown=store(task,markdownPath,"text/markdown",markdownBytes);
        var result=store(task,resultPath,"application/json",structuredBytes);
        var record=new LinkedHashMap<>(Map.<String,Object>ofEntries(
            Map.entry("schemaVersion","runtime_basic_report_receipt.v1"),Map.entry("reportOrigin",LEVEL),
            Map.entry("validationStatus","PASSED"),Map.entry("inputSnapshotVersion",draft.snapshotVersion()),
            Map.entry("inventoryAssetId",source.id()),Map.entry("inventorySha256",source.sha256()),
            Map.entry("factsAssetId",factsAsset.id()),Map.entry("factsSha256",factsAsset.sha256()),
            Map.entry("factsCreatedAt",Instant.ofEpochMilli(factsAsset.createdAt()).toString()),
            Map.entry("analysisStatus",outcome.status().name()),Map.entry("reasonCode",outcome.reasonCode().name()),
            Map.entry("artifacts",List.of(describe(markdown,markdownPath),describe(result,resultPath))),
            Map.entry("verifiedAt",Instant.ofEpochMilli(result.createdAt()).toString())));
        availableStage(task).ifPresent(previous->record.put("basedOnPreliminaryVersion",previous.get("version")));
        assets.store(owner,UserAssetService.Kind.LOG,task.taskId(),scope.runId(),RECEIPT,
            "runtime-basic-report-receipt.json","application/json",bytes(record));
        requireActive(mayPublish);
        disclosure.checkRun(scope.tenantId(),scope.userId(),scope.runId());
        return Optional.of(new Delivered(row,List.of(markdown,result),record));
    }

    private record Draft(Map<String,Object> row,String markdownPath,String resultPath,
            String snapshotVersion,UserAssetService.File inventory,UserAssetService.File facts) {}

    private Optional<Draft> build(CodingTaskContract task,String reason,Instant endedAt) {
        var scope=task.scope();
        var owner=UserAssetService.Owner.of(scope);
        var snapshot=capture.supplyInventory(task).orElse(null);
        if (snapshot==null) return Optional.empty();
        var source=assets.get(owner,snapshot.originalAssetId());
        if (!"DATASET".equals(source.kind()) || source.size()>MAX_INVENTORY_BYTES
                || !snapshot.sha256().equals(source.sha256())
                || !("native-supply-inventory:"+source.sha256()).equals(source.sourceRef()))
            throw new SecurityException("Base report requires a verified Runtime data inventory");
        JsonNode manifest=parse(assets.readTask(scope,task.taskId(),source.id()));
        if (!"native-data-supply-inventory.v1".equals(manifest.path("schemaVersion").asText())
            || manifest.path("basicReport").path("version").asInt()!=1
            || manifest.path("supplyFacts").path("businessDataAvailable").isBoolean()
                && !manifest.path("supplyFacts").path("businessDataAvailable").asBoolean()) return Optional.empty();

        var factsAsset=assets.get(owner,manifest.path("basicReport").path("assetId").asText());
        if (!"DATASET".equals(factsAsset.kind()) || factsAsset.size()>MAX_INVENTORY_BYTES
            || !factsAsset.sha256().equals(manifest.path("basicReport").path("sha256").asText())
            || !("runtime-basic-facts:"+factsAsset.sha256()).equals(factsAsset.sourceRef()))
            throw new SecurityException("Basic report facts differ from the Runtime inventory");
        JsonNode frozenFacts=parse(assets.readTask(scope,task.taskId(),factsAsset.id()));
        if (!"runtime_basic_report_facts.v1".equals(frozenFacts.path("schemaVersion").asText()))
            throw new SecurityException("Basic report facts version is unsupported");
        var state=workspace.get(scope,task.taskId());
        var inputs=new HashMap<String,SiloCodingWorkspaceService.Input>();
        state.inputs().forEach(input->inputs.put(input.path(),input));
        List<JsonNode> datasets=new ArrayList<>();
        boolean hasVerifiedData=false;
        for (var dataset:frozenFacts.path("datasets")) {
            var facts=dataset.path("basicFacts");
            if (!facts.isObject() || "DATA_SUPPLY_STATUS".equals(facts.path("recordType").asText())) continue;
            var input=inputs.get(dataset.path("path").asText());
            if (input==null || input.objectVersion()==null || input.objectVersion().isBlank()
                    || !input.sha256().equals(dataset.path("sha256").asText())
                    || input.size()!=dataset.path("bytes").asLong(-1)
                    || !task.authorization().allowedDataRefs().contains(input.assetId()))
                throw new SecurityException("Base report source differs from the frozen input");
            if (dataset.path("rowCount").asLong()>0 || "VERIFIED_EMPTY_SNAPSHOT".equals(dataset.path("inputStatus").asText()))
                hasVerifiedData=true;
            datasets.add(dataset);
        }
        if (!hasVerifiedData) return Optional.empty();
        String language=Objects.toString(task.runtimeContext().get("analysisReportLanguage"),"AUTO");
        String locale=Locale.forLanguageTag(language).getLanguage();
        if (!"AUTO".equals(language) && !Set.of("zh","en").contains(locale)) return Optional.empty();
        boolean chinese="zh".equals(locale);
        var limitations=endedAt==null
            ? (chinese ? List.of("智能分析尚未完成。本阶段报告包含已核验的数据快照与字段统计。")
                : List.of("Intelligent analysis is pending. This stage contains verified snapshots and observed field statistics."))
            : chinese?List.of("智能分析未完成，仅交付已核验快照与字段统计。",
            "业务归因、目标指标及其他需要声明计算规则的分析仍待完成。")
            :List.of("Intelligent analysis was incomplete. This report covers verified snapshots and observed field statistics.",
                "Business attribution and measures that require declared calculation rules remain open.");
        var row=Map.<String,Object>of("analysisStatus","PARTIAL","reportMarkdown",
            render(frozenFacts.path("objective").asText(),datasets,reason,endedAt,chinese,limitations),
            "evidenceReferences",datasets.stream().map(n->n.path("datasetReference").asText()).distinct().toList(),
            "limitations",limitations,"fieldProvenance",List.of());
        var schema=json.convertValue(manifest.path("resultSchema"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        try { WorkspaceResultSchema.validate(row,schema); }
        catch (IllegalArgumentException unsupported) { return Optional.empty(); }
        JsonNode rules=json.valueToTree(task.runtimeContext().get(CodingRuntimeContext.DELIVERY_RULES));
        String markdownPath=artifactPath(rules,"REPORT","md");
        String resultPath=artifactPath(rules,"RESULT","json");
        if (markdownPath==null || resultPath==null || !NativeDeliveryPolicy.accepts(rules,json.valueToTree(List.of(
            Map.of("path",markdownPath,"type","REPORT"),Map.of("path",resultPath,"type","RESULT"))))) return Optional.empty();

        return Optional.of(new Draft(row,markdownPath,resultPath,state.snapshotVersion(),source,factsAsset));
    }

    void prepare(CodingTaskContract task) {
        if (!supportsBasicReport(task)) return;
        var scope=task.scope();
        disclosure.checkRun(scope.tenantId(),scope.userId(),scope.runId());
        if (capture.readBasicReportStage(task).isPresent()) return;
        build(task,"INTELLIGENT_ANALYSIS_PENDING",null).ifPresent(draft->{
            byte[] content=bytes(draft.row());
            int markdownBytes=draft.row().get("reportMarkdown").toString().getBytes(StandardCharsets.UTF_8).length;
            if ((long)content.length+markdownBytes>task.outputContract().maxResultBytes()) return;
            disclosure.checkBytes(scope.tenantId(),scope.userId(),content);
            var basis=Map.<String,Object>of(
                "schemaVersion",STAGE_SCHEMA,"phase","PRELIMINARY","validationStatus","PASSED",
                "inputSnapshotVersion",draft.snapshotVersion(),"inventoryAssetId",draft.inventory().id(),
                "inventorySha256",draft.inventory().sha256(),"factsAssetId",draft.facts().id(),
                "factsSha256",draft.facts().sha256(),"reportSha256",UserAssetService.hash(content));
            var stored=new LinkedHashMap<>(basis);
            stored.put("version",UserAssetService.hash(bytes(basis)));
            stored.put("report",draft.row());
            capture.freezeBasicReportStage(task,new String(bytes(stored),StandardCharsets.UTF_8));
        });
    }

    Optional<Map<String,Object>> stage(CodingTaskContract task) {
        if (!supportsBasicReport(task)) return Optional.empty();
        var scope=task.scope();
        disclosure.checkRun(scope.tenantId(),scope.userId(),scope.runId());
        return capture.readBasicReportStage(task).map(saved->{
            Map<String,Object> stage=json.convertValue(parse(saved.getBytes(StandardCharsets.UTF_8)),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            var basis=new LinkedHashMap<>(stage);
            var version=basis.remove("version");
            var row=basis.remove("report");
            byte[] content=bytes(row);
            if (!STAGE_SCHEMA.equals(stage.get("schemaVersion")) || !"PRELIMINARY".equals(stage.get("phase"))
                    || !"PASSED".equals(stage.get("validationStatus"))
                    || !workspace.get(scope,task.taskId()).snapshotVersion().equals(stage.get("inputSnapshotVersion"))
                    || !UserAssetService.hash(content).equals(stage.get("reportSha256"))
                    || !UserAssetService.hash(bytes(basis)).equals(version))
                throw new SecurityException("Invalid Runtime preliminary report");
            disclosure.checkBytes(scope.tenantId(),scope.userId(),content);
            return stage;
        });
    }

    Optional<Map<String,Object>> availableStage(CodingTaskContract task) {
        try { return stage(task); }
        catch(RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(NativeBasicReport.class).warn(
                "Preliminary report unreadable taskId={} errorType={}",task.taskId(),unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static boolean supportsBasicReport(CodingTaskContract task) {
        return CodingRuntimeContext.isNativeAnalysis(task.runtimeContext())
            &&task.authorization().networkPolicy()==CodingTaskContract.NetworkPolicy.NONE;
    }

    Optional<Delivered> read(CodingTaskContract task,CodingTaskOutcome outcome) {
        if (!eligible(outcome)) return Optional.empty();
        var scope=task.scope();
        var receipts=assets.list(UserAssetService.Owner.of(scope),UserAssetService.Kind.LOG,task.taskId(),100).stream()
            .filter(file->scope.runId().equals(file.runId())&&RECEIPT.equals(file.sourceRef())&&"AVAILABLE".equals(file.state())).toList();
        if (receipts.isEmpty()) return Optional.empty();
        if (receipts.size()!=1) throw new IllegalStateException("Conflicting Runtime basic report receipts");
        JsonNode receipt=parse(assets.readTask(scope,task.taskId(),receipts.get(0).id()));
        if (!"runtime_basic_report_receipt.v1".equals(receipt.path("schemaVersion").asText())
                || !LEVEL.equals(receipt.path("reportOrigin").asText()) || !"PASSED".equals(receipt.path("validationStatus").asText())
                || !outcome.reasonCode().name().equals(receipt.path("reasonCode").asText())
                || !workspace.get(scope,task.taskId()).snapshotVersion().equals(receipt.path("inputSnapshotVersion").asText())
                || receipt.path("artifacts").size()!=2)
            throw new SecurityException("Invalid Runtime basic report receipt");
        List<UserAssetService.File> files=new ArrayList<>();
        Map<String,Object> row=null;
        for (var entry:receipt.path("artifacts")) {
            var file=assets.get(UserAssetService.Owner.of(scope),entry.path("assetId").asText());
            if (!entry.path("sha256").asText().equals(file.sha256())
                || !Objects.equals(file.sourceRef(),OUTPUT+entry.path("path").asText()))
                throw new SecurityException("Basic report no longer matches its receipt");
            byte[] data=assets.readTask(scope,task.taskId(),file.id());
            if ("application/json".equals(file.contentType()))
                row=json.convertValue(parse(data),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            files.add(file);
        }
        if (row==null) throw new SecurityException("Missing verified structured report");
        return Optional.of(new Delivered(row,files,json.convertValue(receipt,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){})));
    }

    private UserAssetService.File store(CodingTaskContract task,String path,String type,byte[] bytes) {
        var scope=task.scope();
        disclosure.checkBytes(scope.tenantId(),scope.userId(),bytes);
        var file=assets.store(UserAssetService.Owner.of(scope),UserAssetService.Kind.ARTIFACT,task.taskId(),scope.runId(),
            OUTPUT+path,path.substring(path.lastIndexOf('/')+1),type,bytes);
        if (!Arrays.equals(bytes,assets.readTask(scope,task.taskId(),file.id())))
            throw new SecurityException("Runtime basic report readback differs");
        return file;
    }

    private static Map<String,Object> describe(UserAssetService.File file,String path) {
        return Map.of("path",path,"assetId",file.id(),"sha256",file.sha256(),"sizeBytes",file.size());
    }

    private static String artifactPath(JsonNode rules,String type,String format) {
        for (var rule:rules) if (type.equals(rule.path("type").asText())) {
            for (var extension:rule.path("formats")) if (format.equals(extension.asText()))
                return rule.path("directory").asText()+"/runtime-basic-report."+format;
        }
        return null;
    }

    private static void requireActive(BooleanSupplier mayPublish) {
        if (!mayPublish.getAsBoolean()) throw new CancellationException("Basic report publication is no longer authorized");
    }

    private byte[] bytes(Object data) {
        try { return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .writeValueAsBytes(data); }
        catch(IOException failure) { throw new IllegalStateException("Unable to seal Runtime report",failure); }
    }

    private JsonNode parse(byte[] serialized) {
        try { return json.readTree(serialized); }
        catch(IOException invalid) { throw new IllegalArgumentException("Invalid immutable Runtime evidence",invalid); }
    }

    static String render(String objective,List<JsonNode> datasets,String reason,Instant endedAt,boolean zh,List<String> limits) {
        var b=new StringBuilder();
        b.append(zh?"# 可验证基础分析报告\n\n## 业务目标\n":"# Verified basic analysis report\n\n## Objective\n");
        b.append(cell(objective)).append("\n\n");
        b.append(zh?"## 已核验数据快照\n\n| 数据来源 | 模板 | 快照时间 | 行数 | 覆盖状态 | SHA-256 |\n"
            :"## Verified data snapshots\n\n| Dataset | Template | Snapshot time | Rows | Coverage | SHA-256 |\n");
        b.append("|---|---|---|---:|---|---|\n");
        for (var d:datasets) {
            var f=d.path("basicFacts");
            b.append("| ").append(cell(d.path("datasetReference").asText())).append(" | ").append(cell(f.path("template").asText()))
                .append(" | ").append(cell(f.path("snapshotPersistedAt").asText())).append(" | ").append(d.path("rowCount").asLong())
                .append(" | ").append(cell(d.path("inputStatus").asText())).append(" | ").append(cell(d.path("sha256").asText())).append(" |\n");
        }
        long maximum=0;
        for(var d:datasets) maximum=Math.max(maximum,d.path("rowCount").asLong());
        if(datasets.size()>1&&maximum>0) {
            b.append(zh?"\n## 快照行数对照\n\n":"\n## Snapshot row-count comparison\n\n");
            b.append(zh?"| 数据来源 | 行数及比例 |\n":"| Dataset | Rows and relative volume |\n");
            b.append("|---|---|\n");
            for(var d:datasets){
                long rows=d.path("rowCount").asLong();
                int width=rows==0?0:Math.max(1,(int)Math.round(20.0*rows/maximum));
                b.append("| ").append(cell(d.path("datasetReference").asText())).append(" | ")
                    .append("█".repeat(width)).append(" ").append(rows).append(" |\n");
            }
        }
        b.append(zh?"\n## 字段与统计\n\n":"\n## Fields and statistics\n\n");
        for (var dataset:datasets) {
            b.append("### ").append(cell(dataset.path("datasetReference").asText())).append("\n\n");
            if (!dataset.path("fieldInventoryComplete").asBoolean())
                b.append(zh?"字段清单达到显示上限。\n\n":"The field inventory reached its reporting limit.\n\n");
            b.append(zh?"| 字段 | 观测类型 | 非空 | 空值或缺失 | 最小 | 最大 | 合计 |\n"
                :"| Field | Observed types | Non-null | Null or missing | Min | Max | Sum |\n");
            b.append("|---|---|---:|---:|---:|---:|---:|\n");
            dataset.path("basicFacts").path("fields").fields().forEachRemaining(item->{
                var v=item.getValue();var numeric=v.path("numeric");
                b.append("| ").append(cell(item.getKey())).append(" | ").append(cell(v.path("observedTypes").toString())).append(" | ")
                    .append(v.path("nonNull").asLong()).append(" | ").append(v.path("nullOrMissing").asLong()).append(" | ")
                    .append(cell(numeric.path("min").asText())).append(" | ").append(cell(numeric.path("max").asText())).append(" | ")
                    .append(cell(numeric.path("sum").asText())).append(" |\n");
            });
            b.append('\n');
        }
        b.append(zh?"## 执行状态及未完成事项\n\n原因代码：":"## Execution status and outstanding work\n\nReason code: ")
            .append(reason);
        if (endedAt!=null) b.append(zh?"，核对停止时间：":", reconciliation cut-off: ").append(endedAt);
        b.append("\n\n");
        for (String line:limits) b.append("- ").append(line).append('\n');
        return b.toString();
    }

    private static String cell(String text) {
        return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace("|","&#124;").replace("\n"," ").replace("\r"," ")
            .replace("[","&#91;").replace("]","&#93;").replace("`","&#96;");
    }
}
