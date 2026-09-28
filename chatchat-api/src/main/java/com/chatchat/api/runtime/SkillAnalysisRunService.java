package com.chatchat.api.runtime;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class SkillAnalysisRunService {
    private final SkillAnalysisRunRepository repository; private final ObjectMapper mapper;
    public SkillAnalysisRunService(SkillAnalysisRunRepository repository,ObjectMapper mapper){this.repository=repository;this.mapper=mapper;}
    @Transactional
    public String save(SkillRoleContext identity,Object request,Object result) {
        var entity=new SkillAnalysisRunEntity();entity.setId(UUID.randomUUID().toString());
        entity.setTenantId(identity.tenantId());entity.setUserId(identity.userId());entity.setCreatedAt(java.time.Instant.now());
        entity.setRequestJson(write(request));entity.setResultJson(write(result));repository.save(entity);return entity.getId();
    }
    public Map<String,Object> read(String id,SkillRoleContext identity) {
        var entity=owned(id,identity);
        return Map.of("id",id,"createdAt",entity.getCreatedAt(),"revision",entity.getRevision(),
            "request",json(entity.getRequestJson()),"result",json(entity.getResultJson()),
            "review",json(entity.getReviewJson()==null ? "{}" : entity.getReviewJson()));
    }
    @Transactional
    public Map<String,Object> review(String id,SkillRoleContext identity,String status,String notes,long revision) {
        if(!Set.of("APPROVED","NEEDS_REVIEW","REJECTED").contains(status==null ? "" : status)
            || notes==null || notes.length()>4000) throw new IllegalArgumentException("Invalid human review");
        var entity=owned(id,identity);
        if(entity.getRevision()!=revision) throw new IllegalStateException("Review revision changed");
        entity.setReviewJson(write(Map.of("status",status,"notes",notes,"reviewerId",identity.userId(),
            "reviewedAt",java.time.Instant.now().toString())));
        repository.saveAndFlush(entity);return read(id,identity);
    }
    private SkillAnalysisRunEntity owned(String id,SkillRoleContext identity) {
        return repository.findByIdAndTenantIdAndUserId(id,identity.tenantId(),identity.userId())
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
    }
    private String write(Object value){try{return mapper.writeValueAsString(value);}catch(java.io.IOException e){throw new IllegalArgumentException(e);}}
    private JsonNode json(String value){try{return mapper.readTree(value);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
}

