package com.chatchat.api.exploration;

import com.chatchat.api.assets.*;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/data-science/explorations")
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.coding.analysis",name="enabled",havingValue="true")
public class ExplorationWorkspaceController {
    private final ExplorationWorkspaceStore store;
    private final ObjectAccessService access;
    private final UserAssetService assets;
    private final AssetAnalysisController analysis;
    private final ObjectMapper json;
    private final ExplorationAgentBridge agents;
    private final com.chatchat.api.workspace.WorkspaceService workspaces;
    private final com.chatchat.api.workspace.WorkspaceOriginService origins;
    public record Create(String title,String sourceWorkspaceId,String sourceNodeId){
        public Create(String title){this(title,null,null);}
    }
    public record Question(long version,String parentId,List<String> fileIds,String question,String modelName,String agentId){
        public Question(long version,String parentId,List<String> fileIds,String question,String modelName){this(version,parentId,fileIds,question,modelName,null);}
    }
    public record Presentation(long version,boolean saved,Map<String,String> chartTypes){}
    public record Rename(long version,String title){}
    public record Delete(long version){}
    private UserAssetService.Owner owner(HttpServletRequest http){
        workspaces.requireAvailable(http,"data-exploration");
        return new UserAssetService.Owner(access.tenant(http),access.user(http));
    }
    @GetMapping public ApiResponse<?> list(HttpServletRequest http){return ApiResponse.success(store.list(owner(http)));}
    @GetMapping("/agents") public ApiResponse<?> agents(HttpServletRequest http){return ApiResponse.success(agents.options(owner(http)));}
    @PostMapping(value="/attachments",consumes="multipart/form-data") public ApiResponse<?> attachment(HttpServletRequest http,
        @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        var owner=owner(http);
        return ApiResponse.success(assets.upload(owner,file.getOriginalFilename(),file.getBytes()));
    }
    @PostMapping public ApiResponse<?> create(HttpServletRequest http,@RequestBody Create body){
        var owner=owner(http);List<String> files=List.of();
        if(body.sourceWorkspaceId()!=null&&!body.sourceWorkspaceId().isBlank()){
            var source=store.get(owner,body.sourceWorkspaceId());
            files=body.sourceNodeId()==null||body.sourceNodeId().isBlank()?strings(source,"fileIds"):strings(node(source,body.sourceNodeId()),"fileIds");
            for(String id:files){var file=assets.get(owner,id);if(!"AVAILABLE".equals(file.state()))throw new IllegalArgumentException("分析数据暂不可用");}
        }
        return ApiResponse.success(store.create(owner,body.title(),files));
    }
    @GetMapping("/{id}") public ApiResponse<?> get(HttpServletRequest http,@PathVariable("id") String id){var state=store.get(owner(http),id);state.withArray("nodes").forEach(n->linkResult((ObjectNode)n));return ApiResponse.success(state);}
    @PutMapping("/{id}") public ApiResponse<?> rename(HttpServletRequest http,@PathVariable("id") String id,@RequestBody Rename body){
        return ApiResponse.success(store.rename(owner(http),id,body.version(),body.title()));
    }
    @DeleteMapping("/{id}") public ApiResponse<?> delete(HttpServletRequest http,@PathVariable("id") String id,@RequestBody Delete body){
        store.delete(owner(http),id,body.version());return ApiResponse.success(Map.of("deleted",true));
    }
    @PostMapping("/{id}/nodes") public ApiResponse<?> prepare(HttpServletRequest http,@PathVariable("id") String id,@RequestBody Question body){
        var owner=owner(http);var state=store.get(owner,id);version(state,body.version());
        if(body.question()==null||body.question().isBlank()||body.question().length()>8000)throw new IllegalArgumentException("请输入 1–8000 字的分析问题");
        if(state.withArray("nodes").size()>=100)throw new IllegalArgumentException("每个分析项目最多 100 个节点");
        var files=body.fileIds()==null||body.fileIds().isEmpty()?strings(state,"fileIds"):body.fileIds();var contexts=new ArrayList<String>();String parentId=Objects.toString(body.parentId(),"");
        String model=body.modelName(),agentId=Objects.toString(body.agentId(),"").strip();
        if(!agentId.isBlank())agents.authorize(owner,agentId);
        if(!parentId.isBlank()){
            var parent=node(state,parentId);
            if(!parent.path("saved").asBoolean()||!parent.path("receipt").path("resultAvailable").asBoolean()||parent.path("receipt").has("terminal")&&!parent.path("receipt").path("terminal").asBoolean())throw new IllegalArgumentException("请先保存已完成且有真实结果的父节点");
            files=json.convertValue(parent.path("fileIds"),json.getTypeFactory().constructCollectionType(List.class,String.class));
            String inheritedAgent=parent.path("agentId").asText();
            if(!inheritedAgent.isBlank()){
                if(!agentId.isBlank()&&!agentId.equals(inheritedAgent))throw new IllegalArgumentException("切换 Agent 请开始新的分析");
                agentId=inheritedAgent;agents.authorize(owner,agentId);
            }else if(!agentId.isBlank())throw new IllegalArgumentException("历史文件分析未绑定 Agent，请开始新的分析");
            if(agentId.isBlank()){
            parent.path("contextIds").forEach(value->contexts.add(value.asText()));
            String task=parent.path("receipt").path("taskId").asText();
            var context=assets.listPage(owner,UserAssetService.Kind.CONTEXT,task,100,0).stream()
                .filter(file->"user-analysis-result".equals(file.sourceRef())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("父节点历史上下文未归档，无法继续分析"));
            contexts.add(context.id());while(contexts.size()>4)contexts.remove(0);
            model=parent.path("modelName").asText("");
            }
        }
        if(agentId.isBlank()){
        if(files==null||files.isEmpty()||files.size()>8||new HashSet<>(files).size()!=files.size())throw new IllegalArgumentException("请选择 1–8 个数据文件");
        for(String file:files){var asset=assets.get(owner,file);if(!Set.of("DOCUMENT","DATASET","ARTIFACT").contains(asset.kind())||!"AVAILABLE".equals(asset.state()))throw new IllegalArgumentException("数据文件不可用");}
        }
        var combined=new LinkedHashSet<String>(files==null?List.of():files);
        if(body.fileIds()!=null)combined.addAll(body.fileIds());
        files=List.copyOf(combined);
        validateFiles(owner,files);
        var node=state.withArray("nodes").addObject();node.put("id",UUID.randomUUID().toString());node.put("parentId",parentId);
        node.put("question",body.question().strip());node.put("modelName",Objects.toString(model,""));node.put("status","PREPARED");
        long now=System.currentTimeMillis();node.put("createdAt",now);node.put("updatedAt",now);
        if(!agentId.isBlank())node.put("agentId",agentId);
        node.set("fileIds",json.valueToTree(files));node.set("contextIds",json.valueToTree(contexts));node.put("saved",false);node.putObject("chartTypes");
        return ApiResponse.success(store.save(owner,state,body.version()));
    }
    /** Persisted node ID is the existing analysis idempotency key. Refresh can repeat this request. */
    @PostMapping("/{id}/nodes/{nodeId}/execute") public ApiResponse<?> execute(HttpServletRequest http,@PathVariable("id") String id,@PathVariable("nodeId") String nodeId){
        var owner=owner(http);var state=store.get(owner,id);var node=node(state,nodeId);
        if(node.path("receipt").path("terminal").asBoolean())return ApiResponse.success(state);
        if("CANCELLED".equals(node.path("receipt").path("status").asText()))return ApiResponse.success(state);
        if(node.path("receipt").path("resultAvailable").asBoolean())return ApiResponse.success(state);
        if(!node.path("agentId").asText().isBlank()){
            validateFiles(owner,strings(node,"fileIds"));
            // Persist application provenance before a worker can create/update its conversation.
            // The endpoint owns the application identity; a UI header cannot reclassify it.
            origins.prepareForWorkspace(http,"data-exploration",ExplorationAgentBridge.sessionId(node));
            ObjectNode parent=node.path("parentId").asText().isBlank()?null:node(state,node.path("parentId").asText());
            var receipt=agents.submit(owner,node,parent);
            return ApiResponse.success(finish(owner,id,nodeId,receipt,null));
        }
        var request=new AssetAnalysisController.Request(nodeId,strings(node,"fileIds"),strings(node,"contextIds"),node.path("question").asText(),node.path("modelName").asText().isBlank()?null:node.path("modelName").asText());
        // Persist dispatch intent before the long HTTP call, so refresh can read status without submitting again.
        for(int attempt=0;attempt<8;attempt++){
            var current=store.get(owner,id);var persisted=node(current,nodeId);
            if(persisted.has("executionRequestedAt"))break;
            persisted.put("executionRequestedAt",System.currentTimeMillis());
            try{store.save(owner,current,current.path("version").asLong());break;}
            catch(ResponseStatusException conflict){if(conflict.getStatusCode()!=HttpStatus.CONFLICT||attempt==7)throw conflict;}
        }
        ObjectNode result;
        try{result=json.valueToTree(analysis.analyzeVisual(http,request).getData());}
        catch(RuntimeException failure){finish(owner,id,nodeId,null,"执行未完成，可使用原请求恢复；"+failure.getClass().getSimpleName());throw failure;}
        return ApiResponse.success(finish(owner,id,nodeId,result,null));
    }
    private void validateFiles(UserAssetService.Owner owner,List<String> ids){
        if(ids.size()>8)throw new IllegalArgumentException("每次分析最多 8 个附件，请开始新的分析以更换数据");
        long total=0;
        for(String id:ids){var file=assets.get(owner,id);
            if(!"AVAILABLE".equals(file.state())||!Set.of("DOCUMENT","DATASET","ARTIFACT").contains(file.kind()))throw new IllegalArgumentException("分析附件不可用");
            total+=file.size();
        }
        if(total>16L*1024*1024)throw new IllegalArgumentException("本次分析附件总大小不能超过 16 MB");
    }
    @GetMapping("/{id}/nodes/{nodeId}/status") public ApiResponse<?> status(HttpServletRequest http,@PathVariable("id") String id,@PathVariable("nodeId") String nodeId){
        var owner=owner(http);var state=store.get(owner,id);var node=node(state,nodeId);
        boolean uncheckedStage=node.path("agentId").asText().isBlank()&&node.path("receipt").hasNonNull("taskId")
            &&!node.path("receipt").path("resultAvailable").asBoolean()&&!node.path("receipt").path("stageSnapshotChecked").asBoolean();
        if(node.path("receipt").path("terminal").asBoolean()&&!uncheckedStage)return ApiResponse.success(state);
        if(node.path("agentId").asText().isBlank()){
            if(!node.has("executionRequestedAt")&&!node.path("receipt").has("taskId")&&!"RECOVERABLE".equals(node.path("status").asText()))return ApiResponse.success(state);
            var request=new AssetAnalysisController.Request(nodeId,strings(node,"fileIds"),strings(node,"contextIds"),node.path("question").asText(),null);
            var snapshot=analysis.snapshotVisual(http,request);
            if(snapshot.isEmpty())return ApiResponse.success(state);
            ObjectNode receipt=json.valueToTree(snapshot.get());
            return ApiResponse.success(receipt.equals(node.path("receipt"))?state:finish(owner,id,nodeId,receipt,null));
        }
        String task=node.path("receipt").path("agentTaskId").asText();
        if(task.isBlank()||node.path("agentId").asText().isBlank())return ApiResponse.success(state);
        var receipt=agents.snapshot(owner,task,node.path("receipt"));
        if(receipt.equals(node.path("receipt")))return ApiResponse.success(state);
        return ApiResponse.success(finish(owner,id,nodeId,receipt,null));
    }
    @PostMapping("/{id}/nodes/{nodeId}/stop") public ApiResponse<?> stop(HttpServletRequest http,@PathVariable("id") String id,@PathVariable("nodeId") String nodeId){
        var owner=owner(http);var state=store.get(owner,id);var node=node(state,nodeId);
        if(node.path("receipt").path("terminal").asBoolean())return ApiResponse.success(state);
        if(node.path("agentId").asText().isBlank()){
            var request=new AssetAnalysisController.Request(nodeId,strings(node,"fileIds"),strings(node,"contextIds"),node.path("question").asText(),null);
            var receipt=analysis.cancelVisual(http,request).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"尚未确认已受理的分析任务，请等待状态同步后重试停止"));
            return ApiResponse.success(finish(owner,id,nodeId,json.valueToTree(receipt),null));
        }
        return ApiResponse.success(finish(owner,id,nodeId,agents.cancel(owner,node),null));
    }
    private ObjectNode finish(UserAssetService.Owner owner,String id,String nodeId,ObjectNode receipt,String error){
        for(int attempt=0;attempt<8;attempt++){
            var state=store.get(owner,id);long expected=state.path("version").asLong();var node=node(state,nodeId);
            var previous=node.path("receipt");
            boolean terminalStage=previous.path("terminal").asBoolean()&&node.path("agentId").asText().isBlank()
                &&!previous.path("resultAvailable").asBoolean()&&!previous.path("stageSnapshotChecked").asBoolean()
                &&receipt!=null&&receipt.path("terminal").asBoolean()&&receipt.path("stageSnapshotChecked").asBoolean()
                &&previous.path("taskId").asText().equals(receipt.path("taskId").asText())
                &&previous.path("status").asText().equals(receipt.path("status").asText());
            if(previous.path("terminal").asBoolean()&&!terminalStage||receipt==null&&previous.path("resultAvailable").asBoolean()&&node.path("agentId").asText().isBlank())return state;
            if(receipt!=null){
                var prior=node.path("receipt");
                if(prior.hasNonNull("taskId")&&receipt.hasNonNull("taskId")&&!prior.path("taskId").asText().equals(receipt.path("taskId").asText()))
                    throw new SecurityException("Analysis result belongs to a different task");
                if(receipt.path("eventCursor").asLong()<prior.path("eventCursor").asLong())return state;
                // Final failures do not erase a previously verified stage artifact.
                if(prior.path("resultAvailable").asBoolean()&&!receipt.path("resultAvailable").asBoolean()){
                    receipt=receipt.deepCopy();receipt.set("visualReport",prior.path("visualReport"));receipt.put("resultAvailable",true);receipt.put("deliveryStatus","PRESERVED_AFTER_FAILURE");
                    if(receipt.path("terminal").asBoolean()&&receipt.path("visualReport").isObject()&&"SUCCESS".equals(receipt.path("visualReport").path("status").asText())){
                        var report=(ObjectNode)receipt.path("visualReport").deepCopy();report.put("status","PARTIAL");receipt.set("visualReport",report);
                    }
                }
                node.set("receipt",receipt);node.put("status",receipt.path("agentTaskId").asText().isBlank()&&receipt.path("resultAvailable").asBoolean()?"RESULT_AVAILABLE":receipt.path("status").asText("PENDING"));node.remove("error");linkResult(node);
            }
            else{node.put("status","RECOVERABLE");node.put("error",error);}
            node.put("updatedAt",System.currentTimeMillis());
            try{return store.save(owner,state,expected);}catch(ResponseStatusException conflict){if(conflict.getStatusCode()!=HttpStatus.CONFLICT||attempt==7)throw conflict;}
        }throw new IllegalStateException("Workspace update failed");
    }
    /** Deterministic links to existing persisted receipts and artifacts, no new storage/execution subsystem. */
    private static void linkResult(ObjectNode node){
        var receipt=node.path("receipt");var report=receipt.path("visualReport");var execution=report.path("execution");
        if(!report.path("title").asText().isBlank())node.put("title",report.path("title").asText());
        if(!report.path("summary").asText().isBlank())node.put("summary",report.path("summary").asText());
        String context=receipt.path("agentTaskId").asText(receipt.path("taskId").asText());if(!context.isBlank())node.put("contextRef",context);
        node.put("dataSnapshotRef",execution.path("inputSnapshotRef").asText("UNKNOWN"));
        var refs=node.putArray("artifactRefs");
        for(var chart:report.path("charts"))if(!chart.path("artifact").path("fileId").asText().isBlank()){
            var ref=refs.addObject();ref.put("kind","CHART");ref.put("id",chart.path("id").asText());ref.set("artifact",chart.path("artifact"));
        }
        for(var table:report.path("tables")){var ref=refs.addObject();ref.put("kind","TABLE");ref.put("id",table.path("id").asText());ref.put("resultDatasetRef",execution.path("resultDatasetRef").asText());}
    }
    @PutMapping("/{id}/nodes/{nodeId}") public ApiResponse<?> presentation(HttpServletRequest http,@PathVariable("id") String id,@PathVariable("nodeId") String nodeId,@RequestBody Presentation body){
        var owner=owner(http);var state=store.get(owner,id);version(state,body.version());var node=node(state,nodeId);
        if(!node.path("receipt").path("resultAvailable").asBoolean())throw new IllegalArgumentException("尚无可保存的分析结果");
        var types=body.chartTypes()==null?Map.<String,String>of():body.chartTypes();
        Set<String> charts=new HashSet<>();node.path("receipt").path("visualReport").path("charts").forEach(chart->{if("plotly".equals(chart.path("kind").asText()))charts.add(chart.path("id").asText());});
        if(types.size()>32||!charts.containsAll(types.keySet())||!types.values().stream().allMatch(Set.of("bar","line","scatter","pie","donut")::contains))throw new IllegalArgumentException("无效图表配置");
        node.set("chartTypes",json.valueToTree(types));node.put("saved",body.saved());node.put("updatedAt",System.currentTimeMillis());return ApiResponse.success(store.save(owner,state,body.version()));
    }
    private List<String> strings(ObjectNode node,String key){return node.path(key).isArray()?json.convertValue(node.path(key),json.getTypeFactory().constructCollectionType(List.class,String.class)):List.of();}
    private static ObjectNode node(ObjectNode state,String id){for(var node:state.withArray("nodes"))if(id.equals(node.path("id").asText()))return (ObjectNode)node;throw new ResponseStatusException(HttpStatus.NOT_FOUND,"分析节点不存在");}
    private static void version(ObjectNode state,long expected){if(state.path("version").asLong()!=expected)throw new ResponseStatusException(HttpStatus.CONFLICT,"分析项目已更新，请刷新后重试");}
}
