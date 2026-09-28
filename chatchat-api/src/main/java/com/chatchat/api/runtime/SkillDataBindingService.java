package com.chatchat.api.runtime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class SkillDataBindingService {
    private final SkillDataBindingRepository repository;
    private final ObjectMapper mapper;
    public SkillDataBindingService(SkillDataBindingRepository repository,ObjectMapper mapper) {
        this.repository=repository; this.mapper=mapper;
    }
    @Transactional
    public SkillDataBindingEntity draft(String tenant, SkillDataWorkflowProperties.Binding binding, Long revision) {
        if (!tenant.equals(binding.tenantId()) || !binding.enabled())
            throw new IllegalArgumentException("An enabled binding for the authenticated tenant is required");
        var entity=repository.findByTenantIdAndDomainSkillIdAndContractId(tenant,binding.domainSkillId(),binding.contractId()).orElse(null);
        if (entity==null) {
            if (revision!=null) throw new IllegalStateException("Binding revision does not exist");
            entity=new SkillDataBindingEntity(); entity.setId(UUID.randomUUID().toString());
            entity.setTenantId(tenant); entity.setDomainSkillId(binding.domainSkillId()); entity.setContractId(binding.contractId());
        } else if (revision==null || entity.getRevision()!=revision) throw new IllegalStateException("Binding revision changed");
        entity.setDraftJson(write(binding)); return repository.saveAndFlush(entity);
    }
    @Transactional
    public SkillDataBindingEntity publish(String tenant,String skill,String contract,long revision,String publisher) {
        var entity=repository.findByTenantIdAndDomainSkillIdAndContractId(tenant,skill,contract).orElseThrow();
        if(entity.getRevision()!=revision) throw new IllegalStateException("Binding revision changed");
        var draft=read(entity.getDraftJson());
        if (entity.getPublishedJson()!=null && !"{}".equals(entity.getPublishedJson()) && !entity.getPublishedJson().equals(entity.getDraftJson())
            && read(entity.getPublishedJson()).version().equals(draft.version()))
            throw new IllegalArgumentException("A changed binding requires a new workflow version");
        entity.setPublishedJson(entity.getDraftJson()); entity.setPublishedBy(publisher);
        entity.setPublishedAt(java.time.Instant.now()); return repository.saveAndFlush(entity);
    }
    @Transactional
    public void retire(String tenant,String skill,String contract,long revision) {
        var entity=repository.findByTenantIdAndDomainSkillIdAndContractId(tenant,skill,contract).orElseThrow();
        if(entity.getRevision()!=revision) throw new IllegalStateException("Binding revision changed");
        // Keep a tombstone: retiring a database binding must not reactivate a configuration binding.
        entity.setPublishedJson("{}"); repository.saveAndFlush(entity);
    }
    public List<SkillDataBindingEntity> list(String tenant,String skill) {return repository.findByTenantIdAndDomainSkillId(tenant,skill);}
    public List<SkillDataWorkflowProperties.Binding> effective(String tenant,String skill,List<SkillDataWorkflowProperties.Binding> configured) {
        var result=new ArrayList<>(configured);
        for(var entity:list(tenant,skill)) {
            if(entity.getPublishedJson()==null) continue;
            result.removeIf(binding -> tenant.equals(binding.tenantId()) && skill.equals(binding.domainSkillId())
                && entity.getContractId().equals(binding.contractId()));
            if(!"{}".equals(entity.getPublishedJson())) result.add(read(entity.getPublishedJson()));
        }
        return List.copyOf(result);
    }
    private String write(Object value) {try{return mapper.writeValueAsString(value);}catch(java.io.IOException e){throw new IllegalArgumentException(e);}}
    private SkillDataWorkflowProperties.Binding read(String json) {
        try{return mapper.readValue(json,SkillDataWorkflowProperties.Binding.class);}catch(java.io.IOException e){throw new IllegalStateException("Invalid published binding",e);}
    }
}

