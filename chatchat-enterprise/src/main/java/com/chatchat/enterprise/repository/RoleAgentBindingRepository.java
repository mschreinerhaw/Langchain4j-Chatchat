package com.chatchat.enterprise.repository;

import com.chatchat.enterprise.entity.RoleAgentBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleAgentBindingRepository extends JpaRepository<RoleAgentBinding, String> {

    List<RoleAgentBinding> findByRoleId(String roleId);

    List<RoleAgentBinding> findByRoleIdIn(List<String> roleIds);

    void deleteByRoleId(String roleId);
}
