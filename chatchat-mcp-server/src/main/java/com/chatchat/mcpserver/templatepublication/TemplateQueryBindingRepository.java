package com.chatchat.mcpserver.templatepublication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TemplateQueryBindingRepository extends JpaRepository<TemplateQueryBinding, String> {
    List<TemplateQueryBinding> findAllByOrderByUpdatedAtDesc();
    List<TemplateQueryBinding> findByServiceIdAndEnabledTrue(String serviceId);
    boolean existsByServiceIdAndRoleId(String serviceId, String roleId);
    boolean existsByServiceIdAndRoleIdAndIdNot(String serviceId, String roleId, String id);
}
