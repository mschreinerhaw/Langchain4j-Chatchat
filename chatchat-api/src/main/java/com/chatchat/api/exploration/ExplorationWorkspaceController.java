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
    public record Create(String title,String sourceWorkspaceId,String sourceNodeId){
        public Create(String title){this(title,null,null);}
    }
    public record Question(long version,String parentId,List<String> fileIds,String question,String modelName,String agentId){
        public Question(long version,String parentId,List<String> fileIds,String question,String modelName){this(version,parentId,fileIds,question,modelName,null);}
    }
    public record Presentation(long version,boolean saved,Map<String,String> chartTypes){}
    private UserAssetService.Owner owner(HttpServletRequest http){
        workspaces.requireAvailable(http,"data-exploration");
        return new UserAssetService.Owner(access.tenant(http),access.user(http));
    }
    @GetMapping public ApiResponse<?> list(HttpServletRequest http){return ApiResponse.success(store.list(owner(http)));}
    @GetMapping("/agents") public ApiResponse<?> agents(HttpServletRequest http){return ApiResponse.success(agents.options(owner(http)));}
    @PostMapping public ApiResponse<?> create(HttpServletRequest http,@RequestBody Create body){
        var owner=owner(http);List<String> files=List.of();
        if(body.sourceWorkspaceId()!=null&&!body.sourceWorkspaceId().isBlank()){
            var source=store.get(owner,body.sourceWorkspaceId());
            files=body.sourceNodeId()==null||body.sourceNodeId().isBlank()?strings(source,"fileIds"):strings(node(source,body.sourceNodeId()),"fileIds");
            for(String id:files){var file=assets.get(owner,id);if(!"AVAILABLE".equals(file.state()))throw new IllegalArgumentException("分析数据暂不可用");}
        }
        return ApiResponse.success(store.create(owner,body.title(),files));
    }
    @GetMapping("/{id}") public ApiResponse<?> get(HttpServletRequest http,@PathVariable("id") String id){return ApiResponse.success(store.get(owner(http),id));}
    @PostMapping("/{id}/nodes") public ApiResponse<?> prepare(HttpServletRequest http,@PathVariable("id") String id,@RequestBody Question body){
        var owner=owner(http);var state=store.get(owner,id);version(state,body.version());
        if(body.question()==null||body.question().isBlank()||body.question().length()>6000)throw new IllegalArgumentException("请输入 1–6000 字的分析问题");
        if(state.withArray("nodes").size()>=100)throw new IllegalArgumentException("每个分析项目最多 100 个节点");
        var files=body.fileIds()==null||body.fileIds().isEmpty()?strings(state,"fileIds"):body.fileIds();var contexts=new ArrayList<String>();String parentId=Objects.toString(body.parentId(),"");
        String model=body.modelName(),agentId=Objects.toString(body.agentId(),"").strip();
        if(!agentId.isBlank())agents.authorize(owner,agentId);
        if(!parentId.isBlank()){
            var parent=node(state,parentId);
            if(!parent.path("saved").asBoolean()||!parent.path("receipt").path("resultAvailable").asBoolean())throw new IllegalArgumentException("请先保存有真实结果的父节点");
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
        }else{files=List.of();contexts.clear();}
        var node=state.withArray("nodes").addObject();node.put("id",UUID.randomUUID().toString());node.put("parentId",parentId);
        node.put("question",body.question().strip());node.put("modelName",Objects.toString(model,""));node.put("status","PREPARED");
        if(!agentId.isBlank())node.put("agentId",agentId);
        node.set("fileIds",json.valueToTree(files));node.set("contextIds",json.valueToTree(contexts));node.put("saved",false);node.putObject("chartTypes");
        return ApiResponse.success(store.save(owner,state,body.version()));
    }
    /** Persisted node ID is the existing analysis idempotency key. Refresh can repeat this request. */
    @PostMapping("/{id}/nodes/{nodeId}/execute") public ApiResponse<?> execute(HttpServletRequest http,@PathVariable("id") String id,@PathVariable("nodeId") String nodeId){
        var owner=owner(http);var state=store.get(owner,id);var node=node(state,nodeId);
        if(node.path("receipt").path("resultAvailable").asBoolean())return ApiResponse.success(state);
        if(!node.path("agentId").asText().isBlank()){
            ObjectNode parent=node.path("parentId").asText().isBlank()?null:node(state,node.path("parentId").asText());
            var receipt=agents.submit(owner,node,parent);
            return ApiResponse.success(finish(owner,id,nodeId,receipt,null));
        }
        var request=new AssetAnalysisController.Request(nodeId,strings(node,"fileIds"),strings(node,"contextIds"),node.path("question").asText(),node.path("modelName").asText().isBlank()?null:node.path("modelName").asText());
        ObjectNode result;
        try{result=json.valueToTree(analysis.analyzeVisual(http,request).getData());}
        catch(RuntimeException failure){finish(owner,id,nodeId,null,"执行未完成，可使用原请求恢复；"+failure.getClass().getSimpleName());throw failure;}
        return ApiResponse.success(finish(owner,id,nodeId,result,null));
    }
    @GetMapping("/{id}/nodes/{nodeId}/status") public ApiResponse<?> status(HttpServletRequest http,@PathVariable("id") String id,@PathVariable("nodeId") String nodeId){
        var owner=owner(http);var state=store.get(owner,id);var node=node(state,nodeId);
        if(node.path("receipt").path("resultAvailable").asBoolean()||node.path("receipt").path("terminal").asBoolean())return ApiResponse.success(state);
        String task=node.path("receipt").path("agentTaskId").asText();
        if(task.isBlank()||node.path("agentId").asText().isBlank())return ApiResponse.success(state);
        var receipt=agents.snapshot(owner,task);
        if(receipt.equals(node.path("receipt")))return ApiResponse.success(state);
        return ApiResponse.success(finish(owner,id,nodeId,receipt,null));
    }
    private ObjectNode finish(UserAssetService.Owner owner,String id,String nodeId,ObjectNode receipt,String error){
        for(int attempt=0;attempt<8;attempt++){
            var state=store.get(owner,id);long expected=state.path("version").asLong();var node=node(state,nodeId);
            if(node.path("receipt").path("resultAvailable").asBoolean())return state;
            if(receipt!=null){node.set("receipt",receipt);node.put("status",receipt.path("resultAvailable").asBoolean()?"RESULT_AVAILABLE":receipt.path("status").asText("PENDING"));node.remove("error");}
            else{node.put("status","RECOVERABLE");node.put("error",error);}
            try{return store.save(owner,state,expected);}catch(ResponseStatusException conflict){if(conflict.getStatusCode()!=HttpStatus.CONFLICT||attempt==7)throw conflict;}
        }throw new IllegalStateException("Workspace update failed");
    }
    @PutMapping("/{id}/nodes/{nodeId}") public ApiResponse<?> presentation(HttpServletRequest http,@PathVariable("id") String id,@PathVariable("nodeId") String nodeId,@RequestBody Presentation body){
        var owner=owner(http);var state=store.get(owner,id);version(state,body.version());var node=node(state,nodeId);
        if(!node.path("receipt").path("resultAvailable").asBoolean())throw new IllegalArgumentException("尚无可保存的分析结果");
        var types=body.chartTypes()==null?Map.<String,String>of():body.chartTypes();
        Set<String> charts=new HashSet<>();node.path("receipt").path("visualReport").path("charts").forEach(chart->{if("plotly".equals(chart.path("kind").asText()))charts.add(chart.path("id").asText());});
        if(types.size()>32||!charts.containsAll(types.keySet())||!types.values().stream().allMatch(Set.of("bar","line","scatter","pie")::contains))throw new IllegalArgumentException("无效图表配置");
        node.set("chartTypes",json.valueToTree(types));node.put("saved",body.saved());return ApiResponse.success(store.save(owner,state,body.version()));
    }
    private List<String> strings(ObjectNode node,String key){return node.path(key).isArray()?json.convertValue(node.path(key),json.getTypeFactory().constructCollectionType(List.class,String.class)):List.of();}
    private static ObjectNode node(ObjectNode state,String id){for(var node:state.withArray("nodes"))if(id.equals(node.path("id").asText()))return (ObjectNode)node;throw new ResponseStatusException(HttpStatus.NOT_FOUND,"分析节点不存在");}
    private static void version(ObjectNode state,long expected){if(state.path("version").asLong()!=expected)throw new ResponseStatusException(HttpStatus.CONFLICT,"分析项目已更新，请刷新后重试");}
}
