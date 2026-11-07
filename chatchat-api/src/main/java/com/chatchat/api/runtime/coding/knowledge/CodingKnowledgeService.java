package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.*;
import com.chatchat.knowledgebase.search.config.SearchProperties;
import com.chatchat.knowledgebase.search.index.application.PerDocumentIndexService;
import com.chatchat.knowledgebase.search.index.infrastructure.opensearch.OpenSearchDocumentIndexService;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.query.application.TextChunker;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.chatchat.knowledgebase.search.service.SearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;

/** Read-only bridge into the existing index and original store. Index hits never grant disclosure. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public class CodingKnowledgeService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CodingKnowledgeService.class);
    /** Repairable report declarations only; never wraps revoked source authorization. */
    public static final class ReportReferenceException extends SecurityException {
        public ReportReferenceException(String message){super(message);}
    }
    public static final String PROFILE="runtime_os.knowledge.v1";
    public static final Set<String> TOOLS=Set.of("knowledge_search","knowledge_get_document","knowledge_read",
        "knowledge_list_sections","knowledge_related");
    private final SkillExecutionScopePort scopes;
    private final SearchService documents;
    private final PerDocumentIndexService originals;
    private final OpenSearchDocumentIndexService index;
    private final TextChunker chunker;
    private final SearchProperties properties;
    private final DurableCodingTaskService tasks;
    private final JdbcRuntimeEvidenceStore evidence;
    private final ObjectMapper json;
    private com.chatchat.common.retrieval.AgentResourceScopePort resourceScopes;
    private CodingResourceReader resourceReader;
    private CodingInternetAccess internet;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setInternet(CodingInternetAccess value){internet=value;}
    private com.chatchat.api.runtime.coding.data.CodingDataSupplyService dataSupply;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setDataSupply(com.chatchat.api.runtime.coding.data.CodingDataSupplyService value){dataSupply=value;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.chatchat.enterprise.repository.audit.SysAuditLogRepository resourceAudit;
    private void auditAccess(CodingTaskContract c,String tool,String outcome) {
        if(resourceAudit==null)return;
        var record=new com.chatchat.enterprise.entity.audit.SysAuditLog();record.setTenantId(c.scope().tenantId());record.setActorId(c.scope().userId());
        record.setModuleName("RESOURCE_EXPLORATION");record.setActionName("READ_RESOURCE");record.setResourceType("DATA_RESOURCE");
        record.setResourceId(CodingResourceReader.resource(tool));record.setResult(outcome);
        record.setDetail("agentId="+agent(c)+", taskId="+c.taskId()+", tool="+tool+", authorization="+outcome);resourceAudit.save(record);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public void setResourceScopes(com.chatchat.common.retrieval.AgentResourceScopePort value){resourceScopes=value;}
    @org.springframework.beans.factory.annotation.Autowired
    public void setResourceReader(CodingResourceReader value){resourceReader=value;}
    public List<String> allowedTools(String tenant,String user,String agent,boolean approved) {
        if(resourceScopes==null)return TOOLS.stream().sorted().toList(); // Legacy isolated test fixtures only.
        var ids=resourceScopes.resolve(tenant,user,agent,approved);var tools=new TreeSet<String>();
        if(ids.contains("knowledge_documents"))tools.addAll(TOOLS);
        CodingResourceReader.TOOLS.stream().filter(t->ids.contains(CodingResourceReader.resource(t))).forEach(tools::add);
        return List.copyOf(tools);
    }
    private String agent(CodingTaskContract c){return (String)((Map<?,?>)c.runtimeContext().get("knowledgeExploration")).get("agentId");}
    private void requireResource(CodingTaskContract c,String tool) {
        requireResource(c,tool,null);
    }
    private Set<String> currentResources(CodingTaskContract c) {
        if(resourceScopes==null)return null;
        var profile=(Map<?,?>)c.runtimeContext().get("knowledgeExploration");
        return resourceScopes.resolve(c.scope().tenantId(),c.scope().userId(),agent(c),Boolean.TRUE.equals(profile.get("explicitlyRequested")));
    }
    private void requireResource(CodingTaskContract c,String tool,Set<String> current) {
        if(!c.authorization().allowedTools().contains(tool))throw new SecurityException("Resource tool is outside task authorization");
        if(resourceScopes==null) {if(!TOOLS.contains(tool))throw new SecurityException("Resource governor unavailable");return;}
        if(!(current==null?currentResources(c):current).contains(CodingResourceReader.resource(tool)))throw new SecurityException("Data resource authorization was revoked or not requested (ACCESS_REVOKED)");
    }

    public CodingKnowledgeService(SkillExecutionScopePort scopes, SearchService documents, PerDocumentIndexService originals,
            OpenSearchDocumentIndexService index, TextChunker chunker, SearchProperties properties,
            DurableCodingTaskService tasks, JdbcRuntimeEvidenceStore evidence, ObjectMapper json) {
        this.scopes=scopes;this.documents=documents;this.originals=originals;this.index=index;this.chunker=chunker;
        this.properties=properties;this.tasks=tasks;this.evidence=evidence;this.json=json;
    }
    public static boolean enabled(CodingTaskContract c) {
        return CodingProtocol.ANALYSIS_VERSION.equals(c.schemaVersion())
            && c.runtimeContext().get("knowledgeExploration") instanceof Map<?,?> profile
            && PROFILE.equals(profile.get("schemaVersion")) && c.authorization().allowedTools().stream()
                .anyMatch(t->TOOLS.contains(t)||CodingResourceReader.TOOLS.contains(t));
    }
    private record Live(SearchPermissionContext identity, Set<String> ids) { }
    private Live live(CodingTaskContract c) {
        if(!enabled(c))throw new SecurityException("Knowledge tools are outside this task profile");
        String agent=(String)((Map<?,?>)c.runtimeContext().get("knowledgeExploration")).get("agentId");
        // No identity, role or allowed collection is accepted from model arguments.
        var scope=scopes.resolve(c.scope().tenantId(),c.scope().userId(),agent,List.of(),List.of());
        if(!scope.skillAllowed())throw new SecurityException("Agent authorization was revoked");
        var identity=new SearchPermissionContext(c.scope().tenantId(),c.scope().userId(),scope.roles(),agent);
        if(resourceScopes!=null&&!resourceScopes.resolve(c.scope().tenantId(),c.scope().userId(),agent,
                Boolean.TRUE.equals(((Map<?,?>)c.runtimeContext().get("knowledgeExploration")).get("explicitlyRequested"))).contains("knowledge_documents"))return new Live(identity,Set.of());
        if(scope.documentScopeDenied() || scope.managed()&&!scope.hasKnowledgeResources())return new Live(identity,Set.of());
        var ids=new HashSet<String>();
        if(!scope.documentIds().isEmpty()) {
            // The maintained scope already intersects current user grants and Agent bindings.
            // Opening every original here reread the whole bound corpus on each financial callback.
            // A recalled/read/referenced document is still opened and authorized individually below.
            if(scope.documentIds().size()>4096)throw new IllegalStateException("Knowledge scope exceeds bounded retrieval capacity");
            ids.addAll(scope.documentIds());
        } else ids.addAll(documents.authorizedKnowledgeIds(identity));
        if(!scope.tags().isEmpty())ids.removeIf(id->originals.openDocumentIndex(id,identity)
            .map(doc->Collections.disjoint(doc.getTags()==null?List.of():doc.getTags(),scope.tags())).orElse(true));
        if(ids.size()>4096)throw new IllegalStateException("Knowledge scope exceeds bounded retrieval capacity");
        return new Live(identity,Set.copyOf(ids));
    }
    private SearchDocument open(Live scope,String id) {
        if(!scope.ids().contains(id))throw new SecurityException("Document unavailable in current authorization");
        var doc=originals.openDocumentIndex(id,scope.identity()).orElseThrow(()->new SecurityException("Document unavailable"));
        if(!Objects.equals(scope.identity().tenantId(),doc.getTenantId()))throw new SecurityException("Cross-tenant knowledge denied");
        return doc;
    }
    private List<TextChunker.TextChunk> chunks(SearchDocument d) {
        return chunker.splitChunks(d.getContent(),properties.getChunkSize(),properties.getChunkOverlap());
    }
    private String version(SearchDocument d) {
        // Includes original text and metadata; even an in-place update with unchanged version invalidates evidence.
        return ModelProtocolJson.sha256Hex(ModelProtocolJson.compact(Arrays.asList(d.getDocId(),d.getVersion(),
            d.getUpdatedAt(),d.getTitle(),d.getContent(),properties.getChunkSize(),properties.getChunkOverlap())));
    }
    public Map<String,Object> call(CodingTaskContract c,CodingJournal.Lease lease,String tool,String callId,JsonNode args) {
        boolean calculation=c.metadataRefs().contains("runtime-coding:resource-calculation-inputs:v1")
            && c.authorization().allowedTools().contains("python_execute")
            && c.authorization().allowedSideEffects().equals(List.of(CodingTaskContract.SideEffect.CODE_EXECUTION));
        if((!TOOLS.contains(tool)&&!CodingResourceReader.TOOLS.contains(tool))||!enabled(c)
            ||!c.permitsTextResult()&&!calculation&&!CodingInternetAccess.TOOLS.contains(tool))throw new SecurityException("Knowledge tool denied");
        if(CodingInternetAccess.TOOLS.contains(tool)){if(internet==null)throw new SecurityException("Internet service unavailable");internet.require(c,tool);}
        try{requireResource(c,tool);}catch(SecurityException denied){
            auditAccess(c,tool,"DENIED");
            if(callId!=null&&!callId.isBlank()&&callId.length()<=512&&args!=null&&args.isObject()&&args.toString().length()<=10000) {
                String id="resource-denial:"+ModelProtocolJson.sha256Hex(c.taskId()+"\0"+tool+"\0"+callId+"\0"+args);
                var observation=CodingDataObservation.describe(c,tool,json.convertValue(args,Map.class),Map.of(),
                    new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN),java.time.Instant.now(),0);
                var payload=Map.<String,Object>of("taskId",c.taskId(),"tool",tool,"dataObservation",observation,"remoteRequestSent",false);
                if(evidence.find(c.scope(),id).isEmpty())evidence.register(new EvidenceRecord(null,id,c.scope(),"CODING_RESOURCE_DENIAL_RECEIPT",
                    "CodingKnowledgeService",evidence.contentHash(payload),payload,null,0,Map.of()),
                    new EvidenceLineage(null,id,c.scope(),List.of(),c.taskId(),"CodingKnowledgeService",callId,Map.of()));
            }
            throw denied;
        }
        auditAccess(c,tool,"ALLOWED");
        if(callId==null||callId.isBlank()||callId.length()>512||args==null||!args.isObject())
            throw new IllegalArgumentException("Bounded knowledge call required");
        Set<String> fields=CodingInternetAccess.TOOLS.contains(tool)?(tool.equals("web_search")?Set.of("query","search_type","count","language","time_range","provider_id"):Set.of("url","max_content_length","provider_id")):CodingResourceReader.TOOLS.contains(tool)?Set.of("query","security_code","start_time","end_time","industry","limit","offset","article_id","version_id","dataset","filters","start_date","end_date","history_mode","exchange","trade_date","source_id"):switch(tool) {
            case "knowledge_search" -> Set.of("query","top_k","mode","filters");
            case "knowledge_related" -> Set.of("document_id","version_id","top_k");
            case "knowledge_read" -> Set.of("document_id","version_id","chunk_index","limit");
            case "knowledge_list_sections" -> Set.of("document_id","version_id","offset","limit");
            default -> Set.of("document_id","version_id");
        };
        args.fieldNames().forEachRemaining(key->{if(!fields.contains(key))throw new IllegalArgumentException("Unknown knowledge argument");});
        String operation="workspace-"+ModelProtocolJson.sha256Hex(c.taskId()+"\0"+tool+"\0"+callId);
        String intent=ModelProtocolJson.sha256Hex(tool+"\0"+args);
        var reserved=CodingTaskRevisions.mutate(tasks,c.scope(),c.taskId(),r->tasks.reserve(c.scope(),c.taskId(),lease,r,
            operation,intent,CodingJournal.OperationKind.TOOL,new CodingTaskOutcome.Counters(0,0,1,0,0,0,160000,"0")));
        if(!reserved.admitted())throw new IllegalStateException("Knowledge tool budget exhausted");
        CodingTaskRevisions.mutate(tasks,c.scope(),c.taskId(),r->tasks.startIntent(c.scope(),c.taskId(),lease,r,operation,"runtime-workspace:"+operation));
        long bytes=0;boolean resourceResponseReceived=false;
        var readStarted=java.time.Instant.now();long readClock=System.nanoTime();
        RuntimeException readFailure=null;Map<String,Object> observation=null;
        try {
            var scope=live(c);
            validateDisclosed(c,scope,true); // A revoked/updated earlier disclosure cannot remain usable in this task.
            Map<String,Object> result;
            if(CodingResourceReader.TOOLS.contains(tool)){result=resourceCall(c,lease,tool,args,callId);resourceResponseReceived=true;}
            else if(tool.equals("knowledge_search"))result=search(c,lease,scope,args,callId);
            else {
                String id=text(args,"document_id",256), expected=text(args,"version_id",64);
                var doc=open(scope,id);
                if(!version(doc).equals(expected))throw new SecurityException("Document version changed; start a fresh exploration");
                if(tool.equals("knowledge_related")) {
                    var request=json.createObjectNode().put("query",Objects.toString(doc.getTitle(),doc.getFileName()))
                        .put("top_k",integer(args,"top_k",5,1,10));
                    result=search(c,lease,scope,request,callId);
                    result=new LinkedHashMap<>(result);result.put("relationType","LEXICAL_CANDIDATES_NOT_VERIFIED_LINKS");
                } else if(tool.equals("knowledge_get_document")) {
                    result=metadata(doc);result.put("chunk_count",chunks(doc).size());
                    remember(c,lease,doc,-1,"",callId);
                } else {
                    var parts=chunks(doc);var items=new ArrayList<Map<String,Object>>();
                    boolean read=tool.equals("knowledge_read");
                    int offset=integer(args,read?"chunk_index":"offset",0,0,1_000_000);
                    int limit=integer(args,"limit",read?1:30,1,read?4:100);
                    for(int i=offset;i<Math.min(parts.size(),offset+limit);i++) {
                        var part=parts.get(i);var item=new LinkedHashMap<String,Object>();
                        item.put("chunk_id",id+"_"+i);item.put("chunk_index",i);item.put("section_path",part.section());
                        if(read) {
                            item.put("text",part.content());
                            item.put("evidence_id",remember(c,lease,doc,i,part.content(),callId));
                        }
                        items.add(item);
                    }
                    result=metadata(doc);result.put("chunks",items);result.put("next_offset",Math.min(parts.size(),offset+limit));
                    result.put("complete",offset+limit>=parts.size());
                    if(!read)remember(c,lease,doc,-1,"",callId);
                }
            }
            // Check again after index IO, including all documents disclosed by this call.
            validateDisclosed(c,live(c),true);
            result.put("authority","UNTRUSTED_SOURCE_DATA");
            observation=CodingDataObservation.describe(c,tool,json.convertValue(args,Map.class),result,null,
                readStarted,(System.nanoTime()-readClock)/1_000_000);
            var previous=tasks.privateState(c.scope(),c.taskId(),"data-exploration-observations");
            var history=new ArrayList<Map<String,Object>>();
            if(previous!=null&&previous.isArray())for(var item:previous)
                history.add(json.convertValue(item,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
            observation.put("attemptedPaths",List.copyOf(history));
            result.put("dataObservation",observation);
            bytes=json.writeValueAsBytes(result).length;
            if(bytes>150000)throw new IllegalStateException("Knowledge result exceeds disclosure budget");
            return result;
        } catch(java.io.IOException invalid){readFailure=new IllegalStateException("Cannot encode knowledge response",invalid);throw readFailure;}
        catch(RuntimeException invalid){readFailure=invalid;throw invalid;}
        finally {
            String id="knowledge-tool:"+operation;
            var receipt=new LinkedHashMap<String,Object>();receipt.put("taskId",c.taskId());receipt.put("tool",tool);receipt.put("requestSha256",intent);
            receipt.put("operationId",operation);receipt.put("executorStopped",true);receipt.put("executionHandle","runtime-workspace:"+operation);
            if(observation==null||readFailure!=null)observation=CodingDataObservation.describe(c,tool,json.convertValue(args,Map.class),Map.of(),readFailure,
                readStarted,(System.nanoTime()-readClock)/1_000_000);
            // Descriptors may contain Chunk records. Freeze their JSON value before hashing:
            // a decoded Map sorts keys differently from a live record serializer.
            receipt.put("dataObservation",json.convertValue(observation,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
            receipt.put("outputBytes",bytes);
            if(readFailure!=null)receipt.put("failureMessage",Objects.toString(readFailure.getMessage(),"Read failed")
                .replaceAll("(?i)(password|secret|token|authorization)([=: ]+)[^ ,;]+","$1$2[REDACTED]"));
            boolean remote=CodingResourceReader.TOOLS.contains(tool);
            if(remote) {
                receipt.put("executorScope","RUNTIME_HTTP_CLIENT");receipt.put("remoteReadOnly",true);
                receipt.put("remoteResponseReceived",resourceResponseReceived);receipt.put("remoteRequestMayContinue",!resourceResponseReceived);
                receipt.put("remoteSqlExecution",tool.startsWith("market_")?"MAY_READ_FINANCIAL_STORE":"NONE");
                receipt.put("resourceId",CodingResourceReader.resource(tool));receipt.put("agentId",agent(c));
            } else receipt.put("sqlExecuted",false);
            if(evidence.find(c.scope(),id).isEmpty())evidence.register(new EvidenceRecord(null,id,c.scope(),remote?"CODING_RESOURCE_READ_RECEIPT":"CODING_WORKSPACE_TOOL_RECEIPT","CodingKnowledgeService",
                evidence.contentHash(receipt),receipt,null,0,Map.of()),new EvidenceLineage(null,id,c.scope(),List.of(),c.taskId(),"CodingKnowledgeService",callId,Map.of()));
            var priorHistory=tasks.privateState(c.scope(),c.taskId(),"data-exploration-observations");
            var history=json.createArrayNode();
            if(priorHistory!=null&&priorHistory.isArray())for(int i=Math.max(0,priorHistory.size()-15);i<priorHistory.size();i++)history.add(priorHistory.get(i));
            var path=json.createObjectNode().put("tool",tool).put("requestSha256",intent)
                .put("status",Objects.toString(observation.get("status"),"UNKNOWN")).put("evidenceRef","runtime-evidence:"+id);
            path.set("returnedRowCount",json.valueToTree(observation.get("returnedRowCount")));history.add(path);
            tasks.privateState(c.scope(),c.taskId(),lease,"data-exploration-observations",priorHistory,history);
            CodingTaskRevisions.mutate(tasks,c.scope(),c.taskId(),r->tasks.reconcileStopped(c.scope(),c.taskId(),lease,r,operation,"runtime-evidence:"+id));
            long actual=bytes;
            CodingTaskRevisions.mutate(tasks,c.scope(),c.taskId(),r->tasks.settle(c.scope(),c.taskId(),lease,r,operation,
                new CodingTaskOutcome.Counters(0,0,1,0,0,0,actual,"0"),true));
        }
    }
    private Map<String,Object> search(CodingTaskContract c,CodingJournal.Lease lease,Live scope,JsonNode args,String callId) {
        String query=text(args,"query",2000);
        int top=integer(args,"top_k",8,1,10);
        String mode=args.path("mode").asText("bm25");
        if(!Set.of("bm25","hybrid").contains(mode))throw new IllegalArgumentException("Unsupported retrieval mode");
        var allowed=new HashSet<>(scope.ids());
        var filters=args.path("filters");
        if(!filters.isMissingNode()) {
            if(!filters.isObject() || filters.size()>1 || filters.size()==1&&!filters.has("document_type"))throw new IllegalArgumentException("Unsupported search filters");
            if(filters.has("document_type")) {
                var types=filters.path("document_type");
                if(!types.isArray()||types.isEmpty()||types.size()>10)throw new IllegalArgumentException("Bounded document types required");
                var names=new HashSet<String>();types.forEach(t->{if(!t.isTextual()||t.asText().length()>80)throw new IllegalArgumentException("Invalid type");names.add(t.asText());});
                allowed.removeIf(id->!names.contains(open(scope,id).getDocumentType()));
            }
        }
        var hits=new ArrayList<Map<String,Object>>();int candidates=0,stale=0;
        if(!allowed.isEmpty()) {
            if(!index.isAvailable())throw new IllegalStateException("OpenSearch knowledge index unavailable");
            var seen=new HashSet<String>();
            for(var hit:index.searchKnowledge(query,top*3,scope.identity(),allowed.stream().sorted().toList())) {
                candidates++;
                if(!allowed.contains(hit.docId()))continue;
                var doc=open(scope,hit.docId());var parts=chunks(doc);int n=hit.chunkIndex();
                // Never serve stale indexed excerpts, including same-version in-place updates.
                if(n<0||n>=parts.size()||!Objects.equals(parts.get(n).content(),hit.chunkText())
                    ||hit.sourceVersion()!=null&&!Objects.equals(hit.sourceVersion(),doc.getVersion()==null?1:doc.getVersion())){stale++;continue;}
                String content=parts.get(n).content();
                if(!seen.add(doc.getDocId()+"\0"+n))continue;
                var item=metadata(doc);item.put("chunk_id",doc.getDocId()+"_"+n);item.put("chunk_index",n);
                item.put("section_path",parts.get(n).section());item.put("score",hit.score());item.put("text",content);
                item.put("evidence_id",remember(c,lease,doc,n,content,callId));hits.add(item);
                if(hits.size()>=top)break;
            }
        }
        var result=new LinkedHashMap<String,Object>();result.put("hits",hits);result.put("mode_requested",mode);
        result.put("mode_used","bm25");result.put("limitations",List.of("Vector fusion is not enabled for this tool profile; PDF page positions are unavailable."));
        result.put("authorized_document_count",allowed.size());result.put("candidate_count",candidates);result.put("stale_candidate_count",stale);
        result.put("status",allowed.isEmpty()?"NO_AUTHORIZED_DOCUMENTS":hits.isEmpty()?"NO_CURRENT_MATCHES":"MATCHES");
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,"knowledge_retrieval authorized={0} candidates={1} stale={2} delivered={3}",allowed.size(),candidates,stale,hits.size());
        return result;
    }
    private Map<String,Object> metadata(SearchDocument doc) {
        var value=new LinkedHashMap<String,Object>();value.put("document_id",doc.getDocId());value.put("version_id",version(doc));
        value.put("version",doc.getVersion()==null?1:doc.getVersion());value.put("title",Objects.toString(doc.getTitle(),""));
        value.put("document_type",Objects.toString(doc.getDocumentType(),""));value.put("page",null);
        value.put("page_status","UNAVAILABLE_IN_SOURCE_INDEX");
        // Never expose storage paths or arbitrary upstream URLs (which may contain credentials).
        value.put("source_url","/api/v1/search/documents/"+java.net.URLEncoder.encode(doc.getDocId(),java.nio.charset.StandardCharsets.UTF_8));
        return value;
    }
    private String remember(CodingTaskContract c,CodingJournal.Lease lease,SearchDocument doc,int chunk,String content,String callId) {
        var locator=new LinkedHashMap<String,Object>();locator.put("taskId",c.taskId());
        locator.put("agentId",((Map<?,?>)c.runtimeContext().get("knowledgeExploration")).get("agentId"));
        locator.put("document_id",doc.getDocId());locator.put("version_id",version(doc));locator.put("chunk_index",chunk);
        locator.put("textSha256",ModelProtocolJson.sha256Hex(content));
        String id="knowledge:"+ModelProtocolJson.sha256Hex(ModelProtocolJson.compact(locator));
        // Store locators, not source content; resolve through live authorization at every use.
        if(evidence.find(c.scope(),id).isEmpty())evidence.register(new EvidenceRecord(null,id,c.scope(),
            chunk<0?"KNOWLEDGE_DOCUMENT_REFERENCE":"KNOWLEDGE_CHUNK_REFERENCE","CodingKnowledgeService",
            evidence.contentHash(locator),locator,null,0,Map.of()),new EvidenceLineage(null,id,c.scope(),
                List.of(c.inputSnapshotRef().replace("runtime-evidence:","")),c.taskId(),"CodingKnowledgeService",callId,Map.of()));
        String reference="runtime-evidence:"+id;
        var old=tasks.privateState(c.scope(),c.taskId(),"knowledge-disclosures");
        var refs=new TreeSet<String>();if(old!=null)old.forEach(node->refs.add(node.asText()));
        if(refs.add(reference)) {
            if(refs.size()>512)throw new IllegalStateException("Knowledge exploration evidence budget exceeded");
            tasks.privateState(c.scope(),c.taskId(),lease,"knowledge-disclosures",old,json.valueToTree(refs));
        }
        return reference;
    }
    private Map<String,Map<String,Object>> validateDisclosed(CodingTaskContract c,Live scope) {
        return validateDisclosed(c,scope,false);
    }
    private void requireExecutionOpen(CodingTaskContract c) {
        var current=tasks.get(c.scope(),c.taskId());
        if(Thread.currentThread().isInterrupted()||!c.budget().deadlineAt().isAfter(java.time.Instant.now())
            ||current.status().terminal()||current.cancellationRequested())throw new SecurityException("Resource task is no longer executing");
    }
    private Map<String,Map<String,Object>> validateDisclosed(CodingTaskContract c,Live scope,boolean executing) {
        long started=System.nanoTime(),sourceIoNanos=0;
        if(executing)requireExecutionOpen(c);
        var refs=tasks.privateState(c.scope(),c.taskId(),"knowledge-disclosures");
        var reads=new LinkedHashMap<String,CodingResourceReader.Read>();var locators=new LinkedHashMap<String,Map<String,Object>>();
        var authorized=currentResources(c);
        if(refs!=null)for(var ref:refs) {
            var record=evidence.find(c.scope(),ref.asText().substring("runtime-evidence:".length())).orElseThrow(()->new SecurityException("Unknown source"));
            if(!record.payload().containsKey("resource_id"))continue;
            String tool=validateResourceLocator(c,record.payload(),authorized);var args=json.valueToTree(record.payload().get("read_arguments"));
            if(CodingInternetAccess.TOOLS.contains(tool))continue; // Public web citations retain the acquired immutable view, never trigger fresh Internet IO.
            String key=resourceReadKey(tool,args);reads.putIfAbsent(key,new CodingResourceReader.Read(tool,args));locators.putIfAbsent(key,record.payload());
        }
        var current=new LinkedHashMap<String,Map<String,Object>>();var keys=new ArrayList<>(reads.keySet());
        // This map exists only for this validation pass. No source or permission cache crosses requests,
        // tool boundaries or model turns; every reference is authorized again after the batch IO.
        for(int start=0;start<keys.size();start+=16) {
            if(executing)requireExecutionOpen(c);
            var chunk=keys.subList(start,Math.min(start+16,keys.size()));List<Map<String,Object>> bodies;
            if(start>0)authorized=currentResources(c);
            for(String key:chunk)validateResourceLocator(c,locators.get(key),authorized);
            long ioStarted=System.nanoTime();
            try{bodies=resourceReader.readBatch(chunk.stream().map(reads::get).toList());}
            catch(RuntimeException unavailable){
                log.warn("Resource validation unavailable task={} operations={} cause={} status={}",c.taskId(),chunk.size(),
                    unavailable.getClass().getSimpleName(),unavailable instanceof org.springframework.web.server.ResponseStatusException status?status.getStatusCode().value():0);
                throw new SecurityException("Source cannot be revalidated (SOURCE_UNAVAILABLE); retained content is withheld",unavailable);
            }
            finally{sourceIoNanos+=System.nanoTime()-ioStarted;}
            if(bodies==null||bodies.size()!=chunk.size())throw new SecurityException("Incomplete source validation batch");
            authorized=currentResources(c);
            for(String key:chunk)validateResourceLocator(c,locators.get(key),authorized);
            for(int i=0;i<chunk.size();i++)current.put(chunk.get(i),bodies.get(i));
        }
        if(refs!=null)for(var ref:refs){if(executing)requireExecutionOpen(c);validateReference(c,scope,ref.asText(),current,authorized);}
        long elapsedMs=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        if(elapsedMs>=1000)log.info("Resource validation timing task={} references={} reads={} elapsedMs={} sourceIoMs={}",
            c.taskId(),refs==null?0:refs.size(),reads.size(),elapsedMs,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(sourceIoNanos));
        return current;
    }
    public void validateContext(CodingTaskContract c) {if(enabled(c))validateDisclosed(c,live(c));}
    private Map<String,Object> validateReference(CodingTaskContract c,Live scope,String ref) {
        return validateReference(c,scope,ref,Map.of());
    }
    private Map<String,Object> validateReference(CodingTaskContract c,Live scope,String ref,Map<String,Map<String,Object>> current) {
        return validateReference(c,scope,ref,current,null);
    }
    private Map<String,Object> validateReference(CodingTaskContract c,Live scope,String ref,Map<String,Map<String,Object>> current,Set<String> authorized) {
        if(!ref.startsWith("runtime-evidence:knowledge:"))throw new SecurityException("Not a document evidence reference");
        var record=evidence.find(c.scope(),ref.substring("runtime-evidence:".length())).orElseThrow(()->new SecurityException("Unknown knowledge citation"));
        var p=record.payload();
        if(!c.taskId().equals(p.get("taskId")))throw new SecurityException("Cross-task document citation denied");
        if(p.containsKey("resource_id"))return validateResourceReference(c,ref,p,false,current,authorized);
        var doc=open(scope,(String)p.get("document_id"));
        if(!version(doc).equals(p.get("version_id")))throw new SecurityException("Knowledge evidence expired after document update");
        int n=((Number)p.get("chunk_index")).intValue();var parts=chunks(doc);
        if(n>=parts.size()||n< -1||!ModelProtocolJson.sha256Hex(n<0?"":parts.get(n).content()).equals(p.get("textSha256")))
            throw new SecurityException("Knowledge fragment is no longer current");
        var result=metadata(doc);result.put("evidence_id",ref);result.put("chunk_index",n);
        if(n>=0){result.put("chunk_id",doc.getDocId()+"_"+n);result.put("section_path",parts.get(n).section());}
        return result;
    }
    public List<Map<String,Object>> citations(CodingTaskContract c,Collection<?> refs) {
        if(!enabled(c))return List.of();
        var scope=live(c);var current=validateDisclosed(c,scope);var result=new ArrayList<Map<String,Object>>();
        for(Object ref:refs)if(ref instanceof String text&&text.startsWith("runtime-evidence:knowledge:"))result.add(validateReference(c,scope,text,current));
        return List.copyOf(result);
    }
    public Set<String> authorizedReferences(CodingTaskContract c) {
        validateContext(c);var result=new HashSet<String>();var refs=tasks.privateState(c.scope(),c.taskId(),"knowledge-disclosures");
        if(refs!=null)refs.forEach(ref->result.add(ref.asText()));return Set.copyOf(result);
    }
    public void validateReport(CodingTaskContract c,Map<String,Object> report) {
        var disclosed=authorizedReferences(c);
        var refs=report.get("evidenceReferences") instanceof List<?> list?list:List.of();
        if(refs.stream().anyMatch(ref->ref instanceof String text&&text.startsWith("runtime-evidence:knowledge:")&&!disclosed.contains(text)))
            throw new ReportReferenceException("Report contains a document reference not delivered by this task. Copy the exact evidence_id from knowledge tools; remove invented, shortened or other-task IDs and unsupported claims.");
        var cited=citations(c,refs);
        if(!disclosed.isEmpty() && cited.isEmpty())throw new ReportReferenceException("Explored documents require source citations even when conclusions remain unproven. Copy exact evidence_id values returned by knowledge tools.");
        String markdown=Objects.toString(report.get("reportMarkdown"),"");
        var missing=cited.stream().map(citation->citation.get("evidence_id").toString())
            .filter(ref->!markdown.contains("]("+ref+")")).limit(8).toList();
        if(!missing.isEmpty())throw new ReportReferenceException("Report references lack inline citations: "+missing
            +". Place each as [source](evidence_id) next to its supported claim, or remove an unused reference and any unsupported claim. Do not change source IDs.");
    }
    public Map<String,Object> source(CodingTaskContract c,String ref) {
        var scope=live(c);var current=validateDisclosed(c,scope);
        var record=evidence.find(c.scope(),ref.substring("runtime-evidence:".length())).orElseThrow(()->new SecurityException("Unknown source"));
        if(record.payload().containsKey("resource_id"))return validateResourceReference(c,ref,record.payload(),true,current);
        var view=validateReference(c,scope,ref);int n=((Number)view.get("chunk_index")).intValue();
        if(n>=0)view.put("text",chunks(open(scope,(String)view.get("document_id"))).get(n).content());
        view.put("authority","UNTRUSTED_SOURCE_DATA");return view;
    }
    private Map<String,Object> resourceCall(CodingTaskContract c,CodingJournal.Lease lease,String tool,JsonNode args,String callId) {
        requireExecutionOpen(c);
        if(tool.equals("market_get_day_snapshot") && (!c.authorization().allowedTools().contains("python_execute")
            ||!c.metadataRefs().contains("runtime-coding:resource-calculation-inputs:v1")
            ||!(c.runtimeContext().get("inputFiles") instanceof Map<?,?> files)||!"PARQUET".equals(files.get("format"))))
            return new LinkedHashMap<>(Map.of("status","ERROR","errorCode","DAY_SNAPSHOT_REQUIRES_PARQUET_CALCULATION",
                "message","This task has no authorized Parquet calculation delivery. Use bounded market reads or request an appropriately configured task; no data was exported."));
        if(resourceReader==null||args.toString().length()>10000)throw new SecurityException("Bounded resource reader unavailable");
        var result=CodingInternetAccess.TOOLS.contains(tool)?internet.read(c,tool,callId,args):resourceReader.read(tool,args);
        if(CodingInternetAccess.TOOLS.contains(tool)&&"ERROR".equals(result.get("status")))return result;
        if(tool.equals("web_search")){
            if(!(result.get("items") instanceof List<?> items)||items.size()>10)throw new IllegalStateException("Invalid Internet search response");
            var delivered=new ArrayList<Map<String,Object>>();
            for(var item:items){var body=json.convertValue(item,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});String ref=rememberResource(c,lease,tool,args,body,callId);body.put("evidence_id",ref);delivered.add(body);}
            result.put("items",delivered);return result;
        }
        if(tool.equals("news_search")||tool.equals("news_related_events")||tool.equals("market_search")) {
            if(!(result.get("items") instanceof List<?> items)||items.size()>20)throw new IllegalStateException("Invalid resource search response");
            var delivered=new ArrayList<Map<String,Object>>();
            for(Object raw:items) {
                var item=json.convertValue(raw,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
                String readTool;Map<String,Object> read;
                if(tool.startsWith("news_")) {
                    readTool="news_get_article";read=Map.of("article_id",item.get("article_id"),"version_id",item.get("version_id"),"offset",0,"limit",1000,"view","summary");
                } else {readTool="market_get_dataset";read=Map.of("dataset",item.get("dataset_code"));}
                // A catalog hit is a locator, not the canonical current source representation.
                // Bind evidence to exactly the governed source view that later citation reads use.
                var current=new LinkedHashMap<>(resourceReader.read(readTool,json.valueToTree(read)));
                String identity=tool.startsWith("news_")?"article_id":"dataset_code";
                if(!Objects.equals(item.get(identity),current.get(identity)))throw new SecurityException("Search source identity changed");
                if(tool.startsWith("news_")&&!Objects.equals(item.get("version_id"),current.get("version_id")))throw new SecurityException("Search article version changed");
                String ref=rememberResource(c,lease,readTool,json.valueToTree(read),current,callId);
                current.put("evidence_id",ref);delivered.add(current);
            }
            result.put("items",delivered);
        } else {
            String ref=rememberResource(c,lease,tool,args,result,callId);
            // Freeze observations already read through MCP; never let a worker
            // inject its own rows or turn a catalog search into measured data.
            if((tool.startsWith("market_get_")||tool.equals("web_fetch")) && c.authorization().allowedTools().contains("python_execute")) {
                if(dataSupply==null)throw new IllegalStateException("Resource input snapshots unavailable");
                result.put("calculationInput",dataSupply.captureResource(c,lease,ref,new LinkedHashMap<>(result)));
                if(tool.equals("market_get_day_snapshot"))result.remove("items");
            }
            result.put("evidence_id",ref);
        }
        return result;
    }
    private String rememberResource(CodingTaskContract c,CodingJournal.Lease lease,String tool,JsonNode args,Map<String,Object> body,String callId) {
        var locator=new LinkedHashMap<String,Object>();locator.put("taskId",c.taskId());locator.put("agentId",agent(c));
        locator.put("resource_id",CodingResourceReader.resource(tool));locator.put("read_tool",tool);locator.put("read_arguments",args);
        if(CodingInternetAccess.TOOLS.contains(tool))locator.put("source_body",new LinkedHashMap<>(body));
        if(tool.startsWith("market_")) {
            locator.put("resource_hash_schema","market_catalog_heartbeat_v1");
            var catalog=body.get("asset") instanceof Map<?,?> a?a:body;
            if(catalog.get("last_collected_at")!=null)locator.put("catalog_collected_at",catalog.get("last_collected_at"));
            locator.put("response_hash",marketSourceHash(body));
        } else locator.put("response_hash",evidence.contentHash(body));
        String id="knowledge:"+ModelProtocolJson.sha256Hex(ModelProtocolJson.compact(locator));
        if(evidence.find(c.scope(),id).isEmpty())evidence.register(new EvidenceRecord(null,id,c.scope(),
            CodingInternetAccess.TOOLS.contains(tool)?"RESOURCE_INTERNET_REFERENCE":tool.startsWith("news_")?"RESOURCE_NEWS_REFERENCE":"RESOURCE_MARKET_REFERENCE","CodingKnowledgeService",evidence.contentHash(locator),locator,null,0,Map.of()),
            new EvidenceLineage(null,id,c.scope(),List.of(c.inputSnapshotRef().replace("runtime-evidence:","")),c.taskId(),"CodingKnowledgeService",callId,Map.of()));
        String reference="runtime-evidence:"+id;var old=tasks.privateState(c.scope(),c.taskId(),"knowledge-disclosures");var refs=new TreeSet<String>();
        if(old!=null)old.forEach(n->refs.add(n.asText()));
        if(refs.add(reference)) {
            if(refs.size()>512)throw new IllegalStateException("Resource exploration evidence budget exceeded");
            tasks.privateState(c.scope(),c.taskId(),lease,"knowledge-disclosures",old,json.valueToTree(refs));
        }
        return reference;
    }
    private String resourceReadKey(String tool,JsonNode args) {
        return evidence.contentHash(Map.of("tool",tool,"arguments",json.convertValue(args,Map.class)));
    }
    private String validateResourceLocator(CodingTaskContract c,Map<String,Object> locator) {
        return validateResourceLocator(c,locator,null);
    }
    private String validateResourceLocator(CodingTaskContract c,Map<String,Object> locator,Set<String> authorized) {
        if(resourceReader==null||!c.taskId().equals(locator.get("taskId"))||!agent(c).equals(locator.get("agentId")))throw new SecurityException("Resource provenance unavailable");
        String tool=Objects.toString(locator.get("read_tool"),"");
        String admissionTool=tool.equals("market_get_dataset")?"market_search":tool;
        // Search summary evidence is obtainable through search alone; deeper reads have their own task grant.
        if(tool.equals("news_get_article")&&!c.authorization().allowedTools().contains(tool))admissionTool="news_search";
        requireResource(c,admissionTool,authorized);
        if(!CodingResourceReader.resource(tool).equals(locator.get("resource_id")))throw new SecurityException("Invalid source resource");
        var args=json.valueToTree(locator.get("read_arguments"));
        if(!args.isObject()||args.toString().length()>10000)throw new SecurityException("Invalid source locator");
        return tool;
    }
    private Map<String,Object> validateResourceReference(CodingTaskContract c,String ref,Map<String,Object> locator,boolean sourceText) {
        return validateResourceReference(c,ref,locator,sourceText,Map.of());
    }
    private Map<String,Object> validateResourceReference(CodingTaskContract c,String ref,Map<String,Object> locator,boolean sourceText,Map<String,Map<String,Object>> batch) {
        return validateResourceReference(c,ref,locator,sourceText,batch,null);
    }
    private Map<String,Object> validateResourceReference(CodingTaskContract c,String ref,Map<String,Object> locator,boolean sourceText,Map<String,Map<String,Object>> batch,Set<String> authorized) {
        String tool=validateResourceLocator(c,locator,authorized);var args=json.valueToTree(locator.get("read_arguments"));
        if(CodingInternetAccess.TOOLS.contains(tool)){
            if(!(locator.get("source_body") instanceof Map<?,?> body)||!evidence.contentHash(json.convertValue(body,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){})).equals(locator.get("response_hash")))throw new SecurityException("Invalid archived public source");
            var view=new LinkedHashMap<String,Object>();view.put("document_id","web:"+locator.get("response_hash"));view.put("resource_id","internet_search");
            view.put("title",body.getOrDefault("title",null));view.put("document_title",body.get("title"));view.put("source_url",body.get("url"));
            view.put("version_id",locator.get("response_hash"));view.put("version",Objects.toString(body.get("retrieved_at"),locator.get("response_hash").toString().substring(0,12)));
            view.put("section_path",tool.equals("web_search")?"互联网 / 搜索结果摘要":"互联网 / 公开网页正文");view.put("chunk_index",0);view.put("page",null);view.put("evidence_id",ref);
            view.put("published_at",body.get("published_at"));view.put("retrieved_at",body.get("retrieved_at"));view.put("collected_at",body.get("retrieved_at"));view.put("authority","UNTRUSTED_SOURCE_DATA");
            if(sourceText){view.put("text",body.containsKey("content")?body.get("content"):body.get("snippet"));view.put("source_metadata",body);}
            return view;
        }
        Map<String,Object> current;
        try{current=batch.get(resourceReadKey(tool,args));if(current==null)current=resourceReader.read(tool,args);}
        catch(RuntimeException unavailable){throw new SecurityException("Source cannot be revalidated (SOURCE_UNAVAILABLE); retained content is withheld",unavailable);}
        boolean catalogHeartbeat="market_catalog_heartbeat_v1".equals(locator.get("resource_hash_schema"));
        String currentHash=catalogHeartbeat?marketSourceHash(current):evidence.contentHash(current);
        boolean directoryRefresh=false;
        if(!currentHash.equals(locator.get("response_hash"))) {
            if(catalogHeartbeat&&tool.equals("market_resolve_security")) {
                current=verifiedDirectorySnapshot(c,ref,locator,current);directoryRefresh=true;
            } else throw new SecurityException("Resource source changed (SOURCE_CHANGED); start a new task to acquire current evidence");
        }
        if(catalogHeartbeat) {
            current=new LinkedHashMap<>(current);
            if(current.get("asset") instanceof Map<?,?> a) {
                var observedCatalog=new LinkedHashMap<String,Object>();a.forEach((k,v)->observedCatalog.put(k.toString(),v));
                if(locator.containsKey("catalog_collected_at"))observedCatalog.put("last_collected_at",locator.get("catalog_collected_at"));
                else observedCatalog.remove("last_collected_at");current.put("asset",observedCatalog);
            } else if(locator.containsKey("catalog_collected_at"))current.put("last_collected_at",locator.get("catalog_collected_at"));
            else current.remove("last_collected_at");
            current.put("catalog_collection_time_policy","Ingestion heartbeat observed at exploration; not the collection time or version of returned financial observations.");
        }
        var view=new LinkedHashMap<String,Object>();boolean news=tool.startsWith("news_");
        if(directoryRefresh) {
            view.put("source_revalidation_policy","market_security_directory_refresh_v2");
            view.put("source_revalidation_status","VERIFIED_REFRESH_ONLY");
            view.put("source_version_proof",Map.of("originalResponseHash",locator.get("response_hash"),
                "contentHash",marketSourceHash(directoryContent(current))));
        }
        var asset=current.get("asset") instanceof Map<?,?> a?a:current;
        String identity=news?Objects.toString(current.get("article_id"),""):Objects.toString(current.getOrDefault("dataset",current.get("dataset_code")),"");
        view.put("document_id",(news?"news:":"market:")+identity);view.put("resource_id",locator.get("resource_id"));
        view.put("document_title",news?current.get("title"):asset.get("asset_name"));view.put("title",view.get("document_title"));
        view.put("version_id",locator.get("response_hash"));view.put("source_version",news?current.get("published_at"):current.containsKey("data_as_of")?current.get("data_as_of"):asset.get("last_observation_date"));
        view.put("version",Objects.toString(view.get("source_version"),locator.get("response_hash").toString().substring(0,12)));
        view.put("section_path",news?"新闻 / "+current.get("source_view"):"金融数据 / "+identity);view.put("chunk_index",0);view.put("chunk_id",identity+":"+locator.get("response_hash"));
        view.put("evidence_id",ref);view.put("page",null);view.put("page_status","unavailable_in_source");view.put("authority","UNTRUSTED_SOURCE_DATA");
        view.put("source_url",news?current.get("source_url"):"");view.put("source_metadata",current);
        // Metadata cards never contain a second copy of article bodies or financial rows.
        if(!sourceText)view.remove("source_metadata");
        if(sourceText) {
            try{view.put("text",news?current.get("text"):json.writerWithDefaultPrettyPrinter().writeValueAsString(current));}
            catch(java.io.IOException invalid){throw new IllegalStateException("Invalid resource source",invalid);}
        }
        view.put("published_at",current.get("published_at"));
        Object collected=news?current.get("collected_at"):asset.get("last_collected_at");
        if(!news&&current.get("items") instanceof List<?> rows)collected=rows.stream().filter(row->row instanceof Map<?,?>)
            .map(row->((Map<?,?>)row).get("collected_at")).filter(Objects::nonNull).map(Object::toString).max(String::compareTo).orElse(null);
        view.put("collected_at",collected);
        if(!news) {view.put("catalog_collected_at",asset.get("last_collected_at"));view.put("collection_time_policy","Observation collection time when declared; catalog ingestion heartbeat is recorded separately as observed at exploration.");}
        view.put("data_as_of",news?current.get("published_at"):current.containsKey("data_as_of")?current.get("data_as_of"):asset.get("last_observation_date"));view.put("license_status","source_declared_or_unknown_not_a_commercial_license_grant");
        return view;
    }
    /** Compatibility proof for old immutable locators; never rewrites evidence or reports.
     * The original full response must match its OLD hash before any normalization.
     * Live source IO and current grants have already been rechecked by the caller. */
    private Map<String,Object> verifiedDirectorySnapshot(CodingTaskContract c,String ref,Map<String,Object> locator,Map<String,Object> current) {
        var checkpoint=tasks.sdkCheckpoint(c.scope(),c.taskId());
        if(checkpoint!=null)for(var message:checkpoint.path("messages"))for(var part:message.path("content")) {
            var output=part.path("output");
            if(!"tool-result".equals(part.path("type").asText())||!"market_resolve_security".equals(part.path("toolName").asText())
                ||part.path("isError").asBoolean()||!output.isObject()||!ref.equals(output.path("evidence_id").asText()))continue;
            var original=json.convertValue(output,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
            original.remove("evidence_id");original.remove("authority");original.remove("dataObservation"); // Runtime envelope; old full source hash must still match.
            if(!marketSourceHash(original).equals(locator.get("response_hash")))continue;
            if(!marketSourceHash(directoryContent(original)).equals(marketSourceHash(directoryContent(current)))
                ||!directoryTimeAdvanced(original,current))
                throw new SecurityException("Resource source changed (SOURCE_CHANGED); directory content or collection time differs");
            return original; // Retain the exact observed timestamps and source representation.
        }
        throw new SecurityException("Source version proof unavailable (VERSION_PROOF_UNAVAILABLE); retained content is withheld");
    }
    /** Only this directory tool's one-level collection heartbeat is non-content.
     * Observation times, nested metadata and every security/business field remain versioned. */
    private Map<String,Object> directoryContent(Map<String,Object> body) {
        if(!(body.get("items") instanceof List<?> rows)||rows.size()>20)throw new SecurityException("Invalid directory version proof");
        var result=new LinkedHashMap<>(body);var items=new ArrayList<Map<String,Object>>();
        for(Object item:rows) {
            if(!(item instanceof Map<?,?> row))throw new SecurityException("Invalid directory candidate proof");
            var copy=new LinkedHashMap<String,Object>();row.forEach((k,v)->copy.put(k.toString(),v));
            copy.remove("source_refreshed_at");items.add(copy);
        }
        result.put("items",items);return result;
    }
    private boolean directoryTimeAdvanced(Map<String,Object> original,Map<String,Object> current) {
        var before=(List<?>)original.get("items");var after=(List<?>)current.get("items");
        if(before.size()!=after.size())return false;
        for(int i=0;i<before.size();i++) {
            var a=(Map<?,?>)before.get(i);var b=(Map<?,?>)after.get(i);
            try {
                var oldTime=java.time.Instant.parse(Objects.toString(a.get("source_refreshed_at"),""));
                var newTime=java.time.Instant.parse(Objects.toString(b.get("source_refreshed_at"),""));
                if(newTime.isBefore(oldTime))return false;
            }catch(java.time.DateTimeException invalid){return false;}
        }
        return true;
    }
    private String marketSourceHash(Map<String,Object> body) {
        var source=new LinkedHashMap<>(body);
        if(body.get("asset") instanceof Map<?,?> a) {
            var catalog=new LinkedHashMap<String,Object>();a.forEach((k,v)->catalog.put(k.toString(),v));
            catalog.remove("last_collected_at");source.put("asset",catalog);
        } else source.remove("last_collected_at");
        // Only the catalog heartbeat is non-versioned. Row timestamps, values, dates, schema,
        // permissions and compliance metadata retain their existing invalidation behavior.
        return evidence.contentHash(source);
    }
    private static String text(JsonNode args,String name,int max) {
        var node=args.path(name);if(!node.isTextual()||node.asText().isBlank()||node.asText().length()>max)throw new IllegalArgumentException("Invalid "+name);return node.asText();
    }
    private static int integer(JsonNode args,String name,int fallback,int min,int max) {
        if(!args.has(name))return fallback;var node=args.path(name);
        if(!node.isIntegralNumber()||!node.canConvertToInt()||node.asInt()<min||node.asInt()>max)throw new IllegalArgumentException("Invalid "+name);return node.asInt();
    }
}
