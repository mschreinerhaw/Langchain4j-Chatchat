package com.chatchat.mcpserver.templatepublication.binding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TemplateQueryBindingRepository extends JpaRepository<TemplateQueryBinding, String> {
    List<TemplateQueryBinding> findAllByOrderByUpdatedAtDesc();
    List<TemplateQueryBinding> findByServiceIdAndEnabledTrue(String serviceId);
    List<TemplateQueryBinding> findByDomainCode(String domainCode);
    boolean existsByServiceIdAndRoleIdAndDomainCodeAndSubjectTypeAndSubjectId(
        String serviceId, String roleId, String domainCode, String subjectType, String subjectId);
    boolean existsByServiceIdAndRoleIdAndDomainCodeAndSubjectTypeAndSubjectIdAndIdNot(
        String serviceId, String roleId, String domainCode, String subjectType, String subjectId, String id);
}
