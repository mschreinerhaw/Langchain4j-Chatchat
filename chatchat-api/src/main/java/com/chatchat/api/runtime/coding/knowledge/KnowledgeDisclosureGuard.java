package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.runtime.evidence.EvidenceRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Rechecks references embedded in retained answers and downloads; invalidation never rewrites the audit. */
@Service
public class KnowledgeDisclosureGuard {
    private static final Pattern REF=Pattern.compile("runtime-evidence:knowledge:[0-9a-f]{64}");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CodingInvocationContext identity;
    private final DurableCodingTaskService tasks;
    private final JdbcRuntimeEvidenceStore evidence;
    private final CodingKnowledgeService knowledge;
    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeDisclosureGuard(JdbcTemplate jdbc,ObjectMapper json,
            org.springframework.beans.factory.ObjectProvider<CodingInvocationContext> identity,
            org.springframework.beans.factory.ObjectProvider<DurableCodingTaskService> tasks,
            org.springframework.beans.factory.ObjectProvider<JdbcRuntimeEvidenceStore> evidence,
            org.springframework.beans.factory.ObjectProvider<CodingKnowledgeService> knowledge) {
        this(jdbc,json,identity.getIfAvailable(),tasks.getIfAvailable(),evidence.getIfAvailable(),knowledge.getIfAvailable());
    }
    public KnowledgeDisclosureGuard(JdbcTemplate jdbc,ObjectMapper json,CodingInvocationContext identity,
            DurableCodingTaskService tasks,JdbcRuntimeEvidenceStore evidence,CodingKnowledgeService knowledge) {
        this.jdbc=jdbc;this.json=json;this.identity=identity;this.tasks=tasks;this.evidence=evidence;this.knowledge=knowledge;
    }
    public void check(String tenant,String user,String content) {
        if(content==null||!content.contains("runtime-evidence:knowledge:"))return;
        var refs=new HashSet<String>();var matcher=REF.matcher(content);
        while(matcher.find())refs.add(matcher.group());
        // Syntax examples are not source locators. Producer-run checks independently protect truncated results.
        if(refs.isEmpty())return;
        if(tenant==null||user==null||refs.size()>512)throw new SecurityException("Knowledge references require authenticated live access");
        requireLiveValidation();
        var checkedTasks=new HashSet<String>();
        for(String ref:refs) {
            var rows=jdbc.queryForList("select e.record_json from runtime_evidence_entry e join runtime_evidence_partition p on p.id=e.partition_id "
                +"where e.entry_kind='EVIDENCE' and e.entry_id=? and p.tenant_id=? and p.user_id=?",ref.substring("runtime-evidence:".length()),tenant,user);
            if(rows.size()!=1)throw new SecurityException("Knowledge reference unavailable to current user");
            try {
                var candidate=json.readValue((String)rows.get(0).get("record_json"),EvidenceRecord.class);
                var record=evidence.find(candidate.scope(),candidate.evidenceId()).orElseThrow(()->new SecurityException("Knowledge reference unavailable"));
                String task=(String)record.payload().get("taskId");
                if(checkedTasks.add(task))identity.as(record.scope(),Set.of(CodingTaskAccess.Action.READ),()->{
                    knowledge.validateContext(tasks.contract(record.scope(),task));return null;
                });
            }catch(java.io.IOException invalid){throw new SecurityException("Knowledge provenance cannot be verified",invalid);}
        }
    }
    public void checkBytes(String tenant,String user,byte[] content) {
        byte[] marker="runtime-evidence:knowledge:".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for(int i=0;i<=content.length-marker.length;i++) {
            if(content[i]!=marker[0])continue;int n=1;
            while(n<marker.length&&content[i+n]==marker[n])n++;
            if(n==marker.length){check(tenant,user,new String(content,java.nio.charset.StandardCharsets.UTF_8));return;}
        }
    }
    public Map<String,Object> resolve(String tenant,String user,String ref) {
        if(!REF.matcher(ref).matches())throw new SecurityException("Exact knowledge reference required");
        check(tenant,user,ref);
        var rows=jdbc.queryForList("select e.record_json from runtime_evidence_entry e join runtime_evidence_partition p on p.id=e.partition_id "
            +"where e.entry_kind='EVIDENCE' and e.entry_id=? and p.tenant_id=? and p.user_id=?",ref.substring("runtime-evidence:".length()),tenant,user);
        try {
            if(rows.size()!=1)throw new SecurityException("Knowledge source unavailable");
            var record=json.readValue((String)rows.get(0).get("record_json"),EvidenceRecord.class);
            return identity.as(record.scope(),Set.of(CodingTaskAccess.Action.READ),()->knowledge.source(
                tasks.contract(record.scope(),(String)record.payload().get("taskId")),ref));
        }catch(java.io.IOException invalid){throw new SecurityException("Invalid knowledge reference",invalid);}
    }
    /** A truncated task summary may contain no citation text. Its producer run still owns source obligations. */
    public boolean checkRun(String tenant,String viewer,String runId) {
        if(tenant==null||viewer==null||runId==null||runId.isBlank()||runId.length()>256)return false;
        var rows=jdbc.queryForList("select e.record_json from runtime_evidence_entry e join runtime_evidence_partition p on p.id=e.partition_id "
            +"where p.tenant_id=? and p.run_id=? and e.entry_kind='EVIDENCE' "
            +"and e.evidence_type in ('KNOWLEDGE_DOCUMENT_REFERENCE','KNOWLEDGE_CHUNK_REFERENCE','RESOURCE_NEWS_REFERENCE','RESOURCE_MARKET_REFERENCE','RESOURCE_INTERNET_REFERENCE') limit 513",tenant,runId);
        if(rows.size()>512)throw new SecurityException("Knowledge disclosure manifest exceeds budget");
        if(!rows.isEmpty())requireLiveValidation();
        var checked=new HashSet<String>();
        for(var row:rows)try {
            var candidate=json.readValue((String)row.get("record_json"),EvidenceRecord.class);
            if(!viewer.equals(candidate.scope().userId()))throw new SecurityException("Knowledge history belongs to another user");
            var record=evidence.find(candidate.scope(),candidate.evidenceId()).orElseThrow(()->new SecurityException("Unknown source history"));
            String task=(String)record.payload().get("taskId");
            if(checked.add(task))identity.as(record.scope(),Set.of(CodingTaskAccess.Action.READ),()->{
                knowledge.validateContext(tasks.contract(record.scope(),task));return null;
            });
        }catch(java.io.IOException invalid){throw new SecurityException("Invalid knowledge history",invalid);}
        return !rows.isEmpty();
    }
    private void requireLiveValidation() {
        if(identity==null||tasks==null||evidence==null||knowledge==null)
            throw new SecurityException("Knowledge validation is unavailable; retained source content cannot be disclosed");
    }

    /** Bind learned/truncated content to its persisted producer rather than trusting citation text alone. */
    public void checkExperience(String tenant, String user, String experienceId, String taskId) {
        if (tenant == null || user == null || experienceId == null || taskId == null)
            throw new SecurityException("Authenticated experience provenance required");
        var rows = jdbc.queryForList("select e.*, t.execution_attempt_id, t.task_id as producer_task_id "
            + "from agent_experience e left join agent_task_latest t on t.tenant_id=e.tenant_id and t.task_id=e.task_id "
            + "where e.tenant_id=? and e.experience_id=? and e.task_id=?", tenant, experienceId, taskId);
        if (rows.size() != 1 || rows.get(0).get("producer_task_id") == null)
            throw new SecurityException("Experience provenance unavailable");
        var row = rows.get(0);
        // The persisted experience may contain citations absent from its shortened UI summary.
        check(tenant, user, json.valueToTree(row).toString());
        String run = (String) row.get("execution_attempt_id");
        checkRun(tenant, user, run == null || run.isBlank() ? taskId : run);
    }

    public void checkExperienceAggregates(String tenant, String user, java.util.Set<String> validated) {
        if (tenant == null || user == null) throw new SecurityException("Authenticated experience provenance required");
        var rows = jdbc.queryForList("select experience_id, task_id from agent_experience where tenant_id=? limit 513", tenant);
        if (rows.size() > 512) throw new SecurityException("Experience disclosure manifest exceeds budget");
        for (var row : rows) {
            String id = (String) row.get("experience_id");
            if (!validated.contains(id)) checkExperience(tenant, user, id, (String) row.get("task_id"));
        }
    }
    /** Source-tainted memory cannot become ordinary SQL/file context after a task-mode change.
     * Uses existing persisted provenance, including records predating this safeguard. */
    public boolean hasResourceHistory(String tenant,String user,String conversation) {
        if(conversation==null||conversation.isBlank())return false;
        if(tenant==null||user==null||conversation.length()>256)throw new SecurityException("Authenticated conversation provenance required");
        try {
            String quoted=json.writeValueAsString(conversation).replace("!","!!").replace("%","!%").replace("_","!_");
            var rows=jdbc.queryForList("select e.record_json from runtime_evidence_entry e join runtime_evidence_partition p on p.id=e.partition_id "
                +"where p.tenant_id=? and p.user_id=? and e.entry_kind='EVIDENCE' and e.evidence_type in "
                +"('KNOWLEDGE_DOCUMENT_REFERENCE','KNOWLEDGE_CHUNK_REFERENCE','RESOURCE_NEWS_REFERENCE','RESOURCE_MARKET_REFERENCE','RESOURCE_INTERNET_REFERENCE') "
                +"and e.record_json like ? escape '!' limit 513",tenant,user,"%"+quoted+"%");
            if(rows.size()>512)return true;
            for(var row:rows) {
                var record=json.readValue((String)row.get("record_json"),EvidenceRecord.class);
                if(conversation.equals(record.scope().conversationId()))return true;
            }
            return false;
        }catch(java.io.IOException invalid){throw new SecurityException("Conversation provenance unavailable",invalid);}
    }
}
