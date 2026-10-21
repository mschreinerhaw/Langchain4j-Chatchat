package com.chatchat.api.assets;

import com.chatchat.knowledgebase.search.document.infrastructure.extract.DocumentTextExtractor;
import com.chatchat.knowledgebase.search.index.infrastructure.opensearch.OpenSearchDocumentIndexService;
import com.chatchat.knowledgebase.search.model.SearchDocument;
import com.chatchat.knowledgebase.search.security.SearchPermissionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Durable projection into the existing knowledge index. Neither index hits nor index jobs grant file access. */
@Service
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.assets.knowledge",name="enabled",havingValue="true")
public class AssetKnowledgeService {
    public record Status(String fileId,String state,String indexedSha256,int attempts,String errorCode,long updatedAt) { }
    public record Hit(String fileId,String fileName,String sha256,String taskId,String runId,
                      String kind,String excerpt,float score,String evidenceStatus) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final UserAssetService assets;
    private final DocumentTextExtractor parser;
    private final OpenSearchDocumentIndexService index;
    private final ObjectMapper json;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{
        var thread=new Thread(task,"asset-knowledge-index");thread.setDaemon(true);return thread;
    });

    public AssetKnowledgeService(JdbcTemplate jdbc,PlatformTransactionManager manager,UserAssetService assets,
        DocumentTextExtractor parser,OpenSearchDocumentIndexService index,ObjectMapper json) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.assets=assets;this.parser=parser;this.index=index;this.json=json;
    }

    public Status status(UserAssetService.Owner owner,String id) {
        assets.get(owner,id);
        var jobs=jdbc.query("select * from runtime_asset_knowledge_job where file_id=?",(rs,n)->new Status(
            rs.getString("file_id"),rs.getString("state"),rs.getString("indexed_sha256"),rs.getInt("attempts"),
            rs.getString("error_code"),rs.getLong("updated_at")),id);
        if(jobs.isEmpty())return new Status(id,"NOT_APPLICABLE",null,0,null,0);
        return jobs.get(0);
    }

    public List<Status> statuses(UserAssetService.Owner owner) {
        return jdbc.query("select j.* from runtime_asset_knowledge_job j join runtime_workspace_file f on f.id=j.file_id "
            +"where f.workspace_id=? and f.state<>'DELETED' order by j.updated_at desc limit 100",(rs,n)->new Status(
                rs.getString("file_id"),rs.getString("state"),rs.getString("indexed_sha256"),rs.getInt("attempts"),
                rs.getString("error_code"),rs.getLong("updated_at")),owner.id());
    }

    public Status retry(UserAssetService.Owner owner,String id) {
        // Tombstoned files cannot be resurrected by an index retry.
        var file=assets.get(owner,id);
        if(!"AVAILABLE".equals(file.state()))throw new ResponseStatusException(HttpStatus.CONFLICT,"File is not available for indexing");
        jdbc.update("update runtime_asset_knowledge_job set state='PENDING',attempts=0,error_code=null,next_attempt_at=0,updated_at=? where file_id=?",
            System.currentTimeMillis(),id);
        return status(owner,id);
    }

    /** Explicit rebuild after shared-index maintenance; no facts or grants are read from OpenSearch. */
    public int rebuild(UserAssetService.Owner owner) {
        return jdbc.update("update runtime_asset_knowledge_job set state='PENDING',attempts=0,error_code=null,next_attempt_at=0,updated_at=? "
            +"where file_id in (select id from runtime_workspace_file where workspace_id=? and state='AVAILABLE')",System.currentTimeMillis(),owner.id());
    }

    @Scheduled(fixedDelayString="${chatchat.assets.knowledge.poll-ms:5000}")
    public void schedule() {
        // Storage/network work must not block the shared Runtime deadline/recovery scheduler.
        if(!running.compareAndSet(false,true))return;
        try {worker.execute(()->{
            try{drain();}
            catch(RuntimeException error){System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Asset knowledge batch failed; durable jobs remain retryable");}
            finally{running.set(false);}
        });}catch(RejectedExecutionException stopping){running.set(false);}
    }
    @jakarta.annotation.PreDestroy
    public void close(){worker.shutdownNow();}

    public void drain() {
        // A durable row remains retryable after process death; per-job row locks serialize multiple API instances.
        var ids=jdbc.queryForList("select file_id from runtime_asset_knowledge_job where state in ('PENDING','FAILED','DELETE_PENDING') "
            +"and next_attempt_at<=? order by next_attempt_at,file_id limit 10",String.class,System.currentTimeMillis());
        for(String id:ids)process(id);
    }

    void process(String id) {
        tx.executeWithoutResult(s->{
            var jobs=jdbc.queryForList("select * from runtime_asset_knowledge_job where file_id=? for update",id);
            if(jobs.isEmpty())return;
            var job=jobs.get(0);String state=(String)job.get("state");
            if(!Set.of("PENDING","FAILED","DELETE_PENDING").contains(state)
                || ((Number)job.get("next_attempt_at")).longValue()>System.currentTimeMillis())return;
            String failure="INDEX_UNAVAILABLE";
            try {
                if(!index.isAvailable())throw new IllegalStateException("Index is unavailable");
                var rows=jdbc.queryForList("select f.*,w.tenant_id,w.user_id from runtime_workspace_file f join runtime_user_workspace w "
                    +"on f.workspace_id=w.id where f.id=?",id);
                if(rows.isEmpty())throw new IllegalStateException("Registry is missing");
                var row=rows.get(0);
                if("DELETED".equals(row.get("state"))) {
                    index.deleteDocument(id);complete(id,"DELETED",null);return;
                }
                if(!"AVAILABLE".equals(row.get("state")))throw new IllegalStateException("File is not available");
                var owner=new UserAssetService.Owner((String)row.get("tenant_id"),(String)row.get("user_id"));
                var file=assets.get(owner,id);
                boolean history="CONTEXT".equals(file.kind());
                if(!history && !parser.supports(file.fileName())) {complete(id,"SKIPPED",null);return;}
                failure="OBJECT_READ_FAILED";
                byte[] original=assets.read(owner,id); // Exact Silo version, hash and size are verified here.
                failure="PARSE_FAILED";
                String text=history?historyText(original):parser.extractText(new ByteArrayInputStream(original),file.fileName());
                if(text.isBlank())throw new IllegalStateException("No searchable text");
                failure="INDEX_WRITE_FAILED";
                index.indexLatest(SearchDocument.builder().docId(id).title(file.fileName()).fileName(file.fileName())
                    .content(text).source("user-asset:"+id+":"+file.sha256()).version(1).latestVersion(true)
                    .tenantId(owner.tenant()).userId(owner.user()).visibility("private").permissionRoles(List.of())
                    .tags(List.of(history?"analysis-history":"user-document")).build());
                complete(id,"INDEXED",file.sha256());
            }catch(RuntimeException error) {
                int attempts=((Number)job.get("attempts")).intValue()+1;
                long delay=Math.min(3_600_000L,30_000L*(1L<<Math.min(attempts-1,7)));
                // Provider errors may contain text, endpoints or credentials. Persist only the failure category.
                jdbc.update("update runtime_asset_knowledge_job set state=?,attempts=?,error_code=?,next_attempt_at=?,updated_at=? where file_id=?",
                    "DELETE_PENDING".equals(state)?state:"FAILED",attempts,failure,System.currentTimeMillis()+delay,System.currentTimeMillis(),id);
            }
        });
    }

    private void complete(String id,String state,String hash) {
        jdbc.update("update runtime_asset_knowledge_job set state=?,indexed_sha256=?,attempts=0,error_code=null,updated_at=? where file_id=?",
            state,hash,System.currentTimeMillis(),id);
    }

    private String historyText(byte[] bytes) {
        try {
            JsonNode root=json.readTree(bytes);
            String objective=root.path("instruction").asText("");
            String report=root.path("receipt").path("results").path(0).path("reportMarkdown").asText("");
            // Explicit provenance warning stays with retrieved text, independent of model wording.
            return "Historical model analysis; verification must be checked against source evidence.\n"
                +bounded(objective,16000)+"\n"+bounded(report,48000);
        }catch(java.io.IOException error){throw new IllegalStateException("Invalid history document",error);}
    }

    public List<Hit> search(UserAssetService.Owner owner,String query,int limit,boolean historyOnly) {
        if(query==null || query.isBlank() || query.length()>2000)throw new IllegalArgumentException("Provide a query of 1–2000 characters");
        // Fail closed: the old index API treats an empty allowed list as unrestricted.
        var allowed=jdbc.queryForList("select f.id from runtime_workspace_file f join runtime_asset_knowledge_job j on j.file_id=f.id "
            +"where f.workspace_id=? and f.state='AVAILABLE' and j.state='INDEXED' and j.indexed_sha256=f.sha256 "
            +(historyOnly?"and f.kind='CONTEXT' ":"")+"order by f.id limit 1025",String.class,owner.id());
        if(allowed.isEmpty())return List.of();
        if(allowed.size()>1024)throw new ResponseStatusException(HttpStatus.CONFLICT,"Narrow the file search scope before searching more than 1024 indexed files");
        if(!index.isAvailable())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Knowledge search is unavailable");
        var hits=index.search(query,Math.max(1,Math.min(limit,20)),new SearchPermissionContext(owner.tenant(),owner.user(),List.of()),allowed);
        var result=new ArrayList<Hit>();var seen=new HashSet<String>();
        for(var hit:hits) {
            if(!allowed.contains(hit.docId()) || !seen.add(hit.docId()))continue;
            // Current SQL state wins over stale/forged index hits, including deletes during the query.
            var current=jdbc.queryForList("select f.* from runtime_workspace_file f join runtime_asset_knowledge_job j on j.file_id=f.id "
                +"where f.id=? and f.workspace_id=? and f.state='AVAILABLE' and j.state='INDEXED' and j.indexed_sha256=f.sha256",hit.docId(),owner.id());
            if(current.isEmpty())continue;
            var file=assets.get(owner,hit.docId());
            result.add(new Hit(file.id(),file.fileName(),file.sha256(),file.taskId(),file.runId(),file.kind(),bounded(hit.chunkText(),4000),hit.score(),
                "CONTEXT".equals(file.kind())?"HISTORICAL_MODEL_OUTPUT_REQUIRES_SOURCE_VALIDATION":"SOURCE_DOCUMENT"));
        }
        return List.copyOf(result);
    }
    private static String bounded(String text,int max){return text==null?"":text.substring(0,Math.min(text.length(),max));}
}
