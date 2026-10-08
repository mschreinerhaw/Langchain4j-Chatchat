package com.chatchat.mcpserver.datacapability.importing;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface CapabilityImportRepository extends JpaRepository<CapabilityImportBatch, String> {
    List<CapabilityImportBatch> findTop50ByOrderByCreatedAtDesc();
}
