package com.chatchat.mcpserver.admin;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface AdminMenuGrantRepository extends JpaRepository<AdminMenuGrant,String> {
    List<AdminMenuGrant> findByUsername(String username);
    void deleteByUsername(String username);
}
