package com.chatchat.mcpserver.templatepublication.admin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.List;
public interface ToolReleaseRepository extends JpaRepository<ToolRelease,String> {
    List<ToolRelease> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<ToolRelease> findByState(String state);
    boolean existsByBindingId(String bindingId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ToolRelease r where r.logicalId = :logicalId order by r.releaseNumber")
    List<ToolRelease> lockLogical(@org.springframework.data.repository.query.Param("logicalId") String logicalId);
}
