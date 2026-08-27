package com.chatchat.enterprise.repository.security;

import com.chatchat.enterprise.entity.security.RoleAgentBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleAgentBindingRepository extends JpaRepository<RoleAgentBinding, String> {

    List<RoleAgentBinding> findByRoleId(String roleId);

    List<RoleAgentBinding> findByRoleIdIn(List<String> roleIds);

    void deleteByRoleId(String roleId);
}
